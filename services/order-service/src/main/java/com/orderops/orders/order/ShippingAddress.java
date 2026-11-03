package com.orderops.orders.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Embeddable
public record ShippingAddress(
        @NotBlank @Size(max = 120) @Column(name = "shipping_recipient", length = 120)
                String recipient,
        @NotBlank @Size(max = 200) @Column(name = "shipping_line1", length = 200) String line1,
        @NotBlank @Size(max = 100) @Column(name = "shipping_city", length = 100) String city,
        @NotBlank @Size(max = 32) @Column(name = "shipping_postal_code", length = 32)
                String postalCode,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") @Column(name = "shipping_country", length = 2)
                String country) {
    public ShippingAddress {
        check(recipient, 120);
        check(line1, 200);
        check(city, 100);
        check(postalCode, 32);
        if (country == null || !country.matches("[A-Z]{2}"))
            throw new IllegalArgumentException("Country must be two uppercase letters");
    }

    private static void check(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException(
                    "Shipping address fields must be nonblank and within their length limits");
    }
}
