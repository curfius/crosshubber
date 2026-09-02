package com.crosshubber.portal.modules.aihub.telegram;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.crosshubber.portal.common.NodeDates;
import com.crosshubber.portal.modules.aihub.channels.AiHubChannelEntity;
import com.crosshubber.portal.modules.aihub.channels.AiHubChannelsService;
import com.crosshubber.portal.modules.aihub.channels.dto.ChannelCredentials;
import com.crosshubber.portal.modules.aihub.pipeline.ChannelPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * Telegram long-polling lifecycle — mirrors {@code portal/src/modules/ai-hub/channels/polling.ts}:
 * one loop per enabled telegram channel in polling mode, re-reading the channel row each cycle so
 * credential/disable/mode changes apply without restart, backoff 5 s → 60 s, 409 conflicts resolved
 * by deleting the webhook once.
 */
@Service
public class TelegramPollingService {

  private static final Logger log = LoggerFactory.getLogger(TelegramPollingService.class);

  private static final int POLL_HOLD_SECONDS = 25;
  private static final long BACKOFF_INITIAL_MS = 5_000;
  private static final long BACKOFF_MAX_MS = 60_000;

  private final AiHubChannelsService channelsService;
  private final TelegramClient telegramClient;
  private final ChannelPipeline pipeline;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor;
  private final Map<String, Future<?>> running = new ConcurrentHashMap<>();

  public TelegramPollingService(
      AiHubChannelsService channelsService,
      TelegramClient telegramClient,
      ChannelPipeline pipeline,
      ObjectMapper objectMapper) {
    this.channelsService = channelsService;
    this.telegramClient = telegramClient;
    this.pipeline = pipeline;
    this.objectMapper = objectMapper;
    this.executor =
        Executors.newCachedThreadPool(
            r -> {
              Thread thread = new Thread(r, "aihub-poller");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** True when a poll loop is (supposed to be) running for the channel. */
  public boolean isPolling(String channelId) {
    return running.containsKey(channelId);
  }

  /**
   * Starts the poll loop for a telegram channel (idempotent). No-op for channels that are not
   * enabled telegram-polling.
   */
  public boolean startPoller(String channelId) {
    if (running.containsKey(channelId)) {
      return true;
    }
    running.put(channelId, executor.submit(() -> pollLoop(channelId)));
    return true;
  }

  public void stopPoller(String channelId) {
    Future<?> future = running.remove(channelId);
    if (future != null) {
      future.cancel(true);
    }
  }

  /** Boot wiring: starts pollers for every enabled telegram channel in polling mode. */
  @EventListener(ApplicationReadyEvent.class)
  public void startEnabledPollers() {
    try {
      List<String> ids = channelsService.listPollingTelegramIds();
      for (String id : ids) {
        startPoller(id);
      }
      if (!ids.isEmpty()) {
        log.info("[ai-hub] started {} telegram polling loops", ids.size());
      }
    } catch (Exception e) {
      // A telegram outage must never block boot (mirrors void startEnabledPollers())
      log.warn("[ai-hub] failed to start pollers: {}", e.getMessage());
    }
  }

  @PreDestroy
  public void shutdown() {
    for (String id : List.copyOf(running.keySet())) {
      stopPoller(id);
    }
    executor.shutdownNow();
  }

  private void pollLoop(String channelId) {
    AtomicBoolean webhookCleaned = new AtomicBoolean(false);
    long backoff = BACKOFF_INITIAL_MS;
    channelsService.updateStatus(channelId, Map.of("pollRunning", true));
    try {
      while (!Thread.currentThread().isInterrupted()) {
        // Self-healing: re-read the channel each cycle (credentials/mode/enabled).
        AiHubChannelEntity row = channelsService.get(channelId);
        if (row == null
            || !Boolean.TRUE.equals(row.getEnabled())
            || !"telegram".equals(row.getType())
            || !"polling".equals(row.getDeliveryMode())) {
          break;
        }
        try {
          ChannelCredentials creds = channelsService.getCredentials(row);
          if (creds == null || !creds.isTelegram()) {
            throw new IllegalStateException("channel credentials unavailable");
          }
          long offset = statusLong(row, "pollOffset");
          List<TelegramClient.TelegramUpdate> updates =
              telegramClient.getUpdates(creds, offset, POLL_HOLD_SECONDS);
          webhookCleaned.set(true);
          backoff = BACKOFF_INITIAL_MS;

          long newOffset = offset;
          for (TelegramClient.TelegramUpdate update : updates) {
            if (update.message() != null) {
              pipeline.processInboundMessage(
                  channelId,
                  new ChannelPipeline.InboundMessage(
                      update.message().externalChatId(),
                      update.message().externalMessageId(),
                      update.message().text(),
                      update.message().userName()));
            }
            if (update.updateId() >= newOffset) {
              newOffset = update.updateId() + 1;
            }
          }
          Map<String, Object> status = new java.util.LinkedHashMap<>();
          status.put("pollOffset", newOffset);
          status.put("pollLastPollAt", NodeDates.format(Instant.now()));
          status.put("pollRunning", true);
          status.put("lastError", null);
          status.put("lastErrorAt", null);
          channelsService.updateStatus(channelId, status);
        } catch (InterruptedException stop) {
          Thread.currentThread().interrupt();
          break;
        } catch (TelegramClient.TelegramConflictError conflict) {
          if (!webhookCleaned.get()) {
            try {
              ChannelCredentials creds = channelsService.getCredentials(row);
              if (creds != null && creds.isTelegram()) {
                telegramClient.deleteWebhook(creds);
                webhookCleaned.set(true);
                continue;
              }
            } catch (Exception delErr) {
              recordError(channelId, "deleteWebhook failed: " + delErr.getMessage());
            }
          }
          recordError(channelId, conflict.getMessage());
          sleep(backoff);
          backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        } catch (Exception err) {
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
          recordError(channelId, err.getMessage());
          sleep(backoff);
          backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
      }
    } finally {
      channelsService.updateStatus(channelId, Map.of("pollRunning", false));
    }
  }

  private void recordError(String channelId, String message) {
    Map<String, Object> failure = new java.util.LinkedHashMap<>();
    failure.put("lastError", message);
    failure.put("lastErrorAt", NodeDates.format(Instant.now()));
    failure.put("pollRunning", true);
    channelsService.updateStatus(channelId, failure);
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private long statusLong(AiHubChannelEntity row, String key) {
    try {
      var status = objectMapper.readTree(row.getStatus() == null ? "{}" : row.getStatus());
      return status.path(key).asLong(0);
    } catch (Exception e) {
      return 0;
    }
  }
}
