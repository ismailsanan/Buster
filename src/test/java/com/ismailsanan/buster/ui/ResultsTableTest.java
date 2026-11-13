package com.ismailsanan.buster.ui;

import com.ismailsanan.buster.core.EnumResult;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ResultsTableTest {

    private static void onEdt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }

    private static EnumResult hit(String name, int status, long size) {
        return EnumResult.http(name, "", status, size, "file", null);
    }

    private static ResultsTable<EnumResult> table() {
        return new ResultsTable<>(HttpResultsPanel.columns(), "x.csv");
    }

    @Test
    void modelReadsCellsFromRowObjects() {
        ResultTableModel<EnumResult> m = new ResultTableModel<>(HttpResultsPanel.columns());
        m.add(hit("https://h/a", 200, 42));
        assertEquals(1, m.getRowCount());
        assertEquals(5, m.getColumnCount());
        assertEquals("https://h/a", m.getValueAt(0, 0));
        assertEquals(200, m.getValueAt(0, 1));
        assertEquals(42L, m.getValueAt(0, 2));
        assertEquals(Integer.class, m.getColumnClass(1));
        assertFalse(m.isCellEditable(0, 0));
        m.clear();
        assertEquals(0, m.getRowCount());
    }

    @Test
    void addHitFromAnyThreadAndCounter() throws Exception {
        ResultsTable<EnumResult> t = table();
        Thread worker = new Thread(() -> { for (int i = 0; i < 50; i++) t.addHit(hit("u" + i, 200, i)); });
        worker.start();
        worker.join();
        onEdt(() -> {});
        assertEquals(50, t.foundCount());
        assertEquals("50 found", t.counterText());
    }

    @Test
    void sizeAndStatusSortNumerically() throws Exception {
        ResultsTable<EnumResult> t = table();
        t.addHit(hit("a", 200, 1000));
        t.addHit(hit("b", 404, 200));
        t.addHit(hit("c", 301, 30));
        onEdt(() -> t.table().getRowSorter().toggleSortOrder(2));   // Size ascending
        onEdt(() -> {});
        List<Object> sizes = new ArrayList<>();
        for (Object[] row : t.visibleRows()) sizes.add(row[2]);
        assertEquals(List.of(30L, 200L, 1000L), sizes, "string sorting would give 1000, 200, 30");
    }

    @Test
    void filterHidesRowsAndCopyExportUseVisibleRows() throws Exception {
        ResultsTable<EnumResult> t = table();
        t.addHit(hit("a", 200, 1));
        t.addHit(hit("b", 404, 2));
        t.addHit(hit("c", 200, 3));
        onEdt(() -> t.setFilter(r -> r.status() != 404));
        assertEquals(2, t.visibleRows().size());
        assertEquals("3 found, 2 shown", t.counterText());
        onEdt(() -> t.setFilter(null));
        assertEquals(3, t.visibleRows().size());
        assertEquals("3 found", t.counterText());
    }

    @Test
    void selectionReportsTheRowObjectEvenWhenSorted() throws Exception {
        ResultsTable<EnumResult> t = table();
        List<EnumResult> seen = new ArrayList<>();
        t.onSelect(seen::add);
        t.addHit(hit("z", 200, 1));
        t.addHit(hit("a", 200, 2));
        onEdt(() -> t.table().getRowSorter().toggleSortOrder(0));   // "a" first
        onEdt(() -> t.table().setRowSelectionInterval(0, 0));
        assertEquals("a", seen.get(seen.size() - 1).name());
        onEdt(() -> assertEquals(List.of("a"), t.selected().stream().map(EnumResult::name).toList()));
    }

    @Test
    void clearEmptiesTableAndRunsHook() throws Exception {
        ResultsTable<EnumResult> t = table();
        AtomicBoolean hook = new AtomicBoolean();
        t.onClear(() -> hook.set(true));
        t.addHit(hit("a", 200, 1));
        t.clear();
        onEdt(() -> {});
        assertEquals(0, t.foundCount());
        assertTrue(hook.get());
    }

    @Test
    void filterBarBuildsPredicateAndQuickHides() throws Exception {
        List<java.util.function.Predicate<EnumResult>> got = new ArrayList<>();
        HttpFilterBar[] bar = new HttpFilterBar[1];
        onEdt(() -> bar[0] = new HttpFilterBar(got::add));
        onEdt(() -> bar[0].hideStatus(404));
        var p = got.get(got.size() - 1);
        assertFalse(p.test(hit("x", 404, 5)));
        assertTrue(p.test(hit("x", 200, 5)));
        onEdt(() -> bar[0].hideSize(5));
        p = got.get(got.size() - 1);
        assertFalse(p.test(hit("x", 200, 5)));
        assertTrue(p.test(hit("x", 200, 6)));
    }
}
