package dev.trustedvideos.proof.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class PinHashTest {
    @Test public void saltedVerifiersRejectWrongPinsAndMalformedStorage() {
        String first = PinHash.create("582901"), second = PinHash.create("582901");
        assertNotEquals(first, second);
        assertTrue(PinHash.verify("582901", first));
        assertFalse(PinHash.verify("582902", first));
        assertFalse(PinHash.verify("582901", "broken"));
        assertFalse(PinHash.verify("582901", first.substring(0, first.length() - 2)));
        assertThrows(IllegalArgumentException.class, () -> PinHash.create("123"));
    }
}
