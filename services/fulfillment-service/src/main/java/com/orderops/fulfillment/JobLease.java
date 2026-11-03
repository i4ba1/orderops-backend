package com.orderops.fulfillment;

import java.util.UUID;

record JobLease(Command command, UUID token, int attempts) {}
