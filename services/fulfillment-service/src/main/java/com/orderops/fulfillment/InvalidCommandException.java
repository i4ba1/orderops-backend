package com.orderops.fulfillment;

class InvalidCommandException extends RuntimeException {
    InvalidCommandException() {
        super("Invalid fulfillment command envelope or payload");
    }
}
