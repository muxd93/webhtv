package com.fongmi.android.tv.setting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InterfaceOrderStoreTest {

    @Test
    public void normalizeRemovesBlankAndDuplicateUrlsInOrder() {
        assertEquals(
                Arrays.asList("a", "b", "c"),
                InterfaceOrderStore.normalize(Arrays.asList("a", "b", "a", "", "c", "b")));
    }

    @Test
    public void normalizePreservesEmptyListContract() {
        assertTrue(InterfaceOrderStore.normalize(new ArrayList<>()).isEmpty());
    }

    @Test
    public void sortUsesSavedOrderThenAppendsNewUrls() {
        assertEquals(
                Arrays.asList("c", "a", "b"),
                InterfaceOrderStore.sortUrls(
                        Arrays.asList("a", "b", "c"),
                        Arrays.asList("c", "deleted", "a")));
    }

    @Test
    public void sortDeduplicatesAvailableAndSavedUrls() {
        assertEquals(
                Arrays.asList("b", "a"),
                InterfaceOrderStore.sortUrls(
                        Arrays.asList("a", "b", "a"),
                        Arrays.asList("b", "b", "missing")));
    }

    @Test
    public void sortUrlsHealthUnknownKeepsBaseOrder() {
        assertEquals(
                Arrays.asList("b", "a"),
                InterfaceOrderStore.sortUrls(
                        Arrays.asList("a", "b"),
                        Arrays.asList("b"),
                        new HashMap<>()));
    }

    @Test
    public void sortUrlsSinksUnhealthyAfterHealthy() {
        Map<String, Boolean> health = new HashMap<>();
        health.put("a", false);
        assertEquals(
                Arrays.asList("b", "c", "a"),
                InterfaceOrderStore.sortUrls(
                        Arrays.asList("a", "b", "c"),
                        new ArrayList<>(),
                        health));
    }

    @Test
    public void sortUrlsHealthRespectsSavedOrderWithinBuckets() {
        Map<String, Boolean> health = new HashMap<>();
        health.put("c", false);
        assertEquals(
                Arrays.asList("b", "a", "c"),
                InterfaceOrderStore.sortUrls(
                        Arrays.asList("a", "b", "c"),
                        Arrays.asList("b"),
                        health));
    }
}
