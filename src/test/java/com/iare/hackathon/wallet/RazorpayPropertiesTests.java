package com.iare.hackathon.wallet;

import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class RazorpayPropertiesTests {
    @Test void onlyTwoCredentialEntriesAreRequiredAndBlankKeysDisableRecharges() throws Exception {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals(Set.of("razorpay.key.id", "razorpay.key.secret"), properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("razorpay.")).collect(Collectors.toSet()));
        new ApplicationContextRunner().withUserConfiguration(RazorpayProperties.class)
                .withPropertyValues("razorpay.key.id=", "razorpay.key.secret=")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(RazorpayProperties.class).configured());
                });
    }

    @Test void backendLoadsBothCredentialValuesThroughSpringProperties() {
        String keyId = UUID.randomUUID().toString();
        String secret = UUID.randomUUID().toString();
        new ApplicationContextRunner().withUserConfiguration(RazorpayProperties.class)
                .withPropertyValues("razorpay.key.id=" + keyId, "razorpay.key.secret=" + secret)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var properties = context.getBean(RazorpayProperties.class);
                    assertTrue(properties.configured());
                    assertEquals(keyId, properties.keyId);
                    assertEquals(secret, properties.keySecret);
                });
    }
}
