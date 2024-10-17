package org.apache.nifi.processors.slack.util;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;

import java.util.Arrays;
import java.util.List;

public class ChannelIDValidator {
    public static List<ValidationResult> getChannelIdValidationResult(final ValidationContext validationContext,
                                                                      final PropertyDescriptor propertyDescriptor) {
        final String channelsValue = validationContext.getProperty(propertyDescriptor).getValue();

        return Arrays.stream(channelsValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .filter(s -> !isAllUpperCaseAndNumbers(s))
                .map(channelId ->
                        new ValidationResult.Builder()
                                .subject(propertyDescriptor.getDisplayName())
                                .valid(false)
                                .explanation("Channel ID [" + channelId + "] is not valid, a Slack channel ID must contain uppercase letters and numbers only. "
                                        + "In case you provided a channel name, please add # at the beginning.")
                                .build())
                .toList();
    }

    private static boolean isAllUpperCaseAndNumbers(final String id) {
        return id.toUpperCase().equals(id);
    }
}
