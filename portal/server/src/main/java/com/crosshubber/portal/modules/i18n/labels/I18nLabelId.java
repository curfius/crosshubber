package com.crosshubber.portal.modules.i18n.labels;

import java.io.Serializable;
import java.util.Objects;

/** Composite PK for {@link I18nLabelEntity}. */
public class I18nLabelId implements Serializable {

  private String languageCode;
  private String key;

  public I18nLabelId() {}

  public I18nLabelId(String languageCode, String key) {
    this.languageCode = languageCode;
    this.key = key;
  }

  public String getLanguageCode() {
    return languageCode;
  }

  public void setLanguageCode(String languageCode) {
    this.languageCode = languageCode;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    I18nLabelId that = (I18nLabelId) o;
    return Objects.equals(languageCode, that.languageCode) && Objects.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(languageCode, key);
  }
}
