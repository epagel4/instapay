package com.instapay.payment.domain.pixkey;

import com.instapay.payment.domain.shared.Cpf;
import com.instapay.payment.domain.shared.DomainException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Each key type knows how to validate and normalize its raw value. */
public enum PixKeyType {
  CPF {
    @Override
    String normalize(String raw) {
      return new Cpf(raw).value();
    }
  },
  EMAIL {
    private static final int MAX_LENGTH = 77; // Pix DICT limit
    private static final Pattern FORMAT =
        Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$");

    @Override
    String normalize(String raw) {
      String email = raw.strip().toLowerCase(Locale.ROOT);
      return check(email.length() <= MAX_LENGTH && FORMAT.matcher(email).matches(), email);
    }
  },
  PHONE {
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    @Override
    String normalize(String raw) {
      String phone = raw.replaceAll("[\\s()-]", "");
      return check(E164.matcher(phone).matches(), phone);
    }
  },
  RANDOM {
    private static final Pattern UUID_FORMAT =
        Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Override
    String normalize(String raw) {
      String key = raw.strip().toLowerCase(Locale.ROOT);
      return check(UUID_FORMAT.matcher(key).matches(), key);
    }
  };

  abstract String normalize(String raw);

  String check(boolean valid, String normalized) {
    if (!valid) {
      throw new DomainException("pixkey.invalid", "Invalid " + name() + " key");
    }
    return normalized;
  }
}
