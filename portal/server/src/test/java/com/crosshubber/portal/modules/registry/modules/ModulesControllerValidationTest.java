package com.crosshubber.portal.modules.registry.modules;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.crosshubber.portal.config.GlobalExceptionHandler;
import com.crosshubber.portal.modules.registry.dto.ModuleDto;

/** Bean-validation errors surface as the portal {@code {"error":"..."}} envelope with 400. */
class ModulesControllerValidationTest {

  private ModulesService modulesService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    modulesService = mock(ModulesService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new ModulesController(modulesService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void invalidModuleKeyReturns400EnvelopeAndNeverCallsService() throws Exception {
    mvc.perform(
            post("/api/registry/modules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"BAD_KEY\",\"name\":\"X\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    org.mockito.Mockito.verify(modulesService, never()).upsert(any());
  }

  @Test
  void validBodyReachesTheService() throws Exception {
    when(modulesService.upsert(any())).thenReturn(new ModuleEntity());
    when(modulesService.toOutput(any()))
        .thenReturn(
            new ModuleDto(
                "k", "N", true, false, "manual", null, null, null, null, null, null, null, null));

    mvc.perform(
            post("/api/registry/modules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"name\":\"N\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.module.key").value("k"));
  }
}
