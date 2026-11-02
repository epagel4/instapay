/**
 * Payment use cases and ports. Inbound ports are the use-case interfaces; outbound ports are the
 * interfaces implemented by adapters (persistence, messaging, clock).
 *
 * <p>Depends only on the domain. No framework annotations (see ADR-001).
 */
package com.instapay.payment.application;
