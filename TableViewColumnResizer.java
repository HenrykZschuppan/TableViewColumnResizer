/*
 * Copyright (c) 2025 Henryk Daniel Zschuppan, Mecklenburg-Vorpommern, Germany
 *
 * Developed in Mecklenburg-Vorpommern, Germany.
 *
 * --- MIT License ---
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package izon.framework.tableview; // Adjust package name if necessary

// SLF4j Imports
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Automatically resizes the columns of a JavaFX TableView to proportionally fill the
 * available horizontal space, while respecting column minimum and maximum widths.
 * It correctly accounts for the vertical scrollbar's width when it becomes visible.
 * <p>
 * Use the static {@link #install(TableView)} method to attach this behavior to a TableView.
 * This method automatically sets the TableView's column resize policy to
 * {@link TableView#UNCONSTRAINED_RESIZE_POLICY}, which is required for the resizer
 * to function correctly.
 * <p>
 * The resizer operates automatically by listening to changes in the TableView's width,
 * visible columns, and the vertical scrollbar's state. Due to JavaFX layout timing,
 * there might be a brief visual adjustment (flicker) upon initial display or when the
 * scrollbar appears/disappears, as the resize calculation is triggered *after*
 * the state change is detected by the listeners.
 * <p>
 * **Logging:** Uses SLF4j for internal logging. Logging output can be globally
 * enabled or disabled for all instances via the static {@link #isLoggingEnabled} flag
 * (defaults to {@code true}). For efficiency ("lazy logging"), log messages are only
 * constructed and output if both this flag is {@code true} AND the corresponding
 * logging level (e.g., DEBUG, INFO, WARN) is enabled in the configured SLF4j binding.
 * A suitable SLF4j binding (e.g., slf4j-simple, logback-classic) must be present
 * on the classpath at runtime to see log output.
 * <p>
 * **Efficiency Considerations:** Care has been taken in the implementation to balance
 * accurate resizing with performance. Key efficiency measures include:
 * <ul>
 *     <li>Debouncing reactions to rapid table width changes.</li>
 *     <li>Minimizing potentially expensive node lookups.</li>
 *     <li>Conditional application of calculated preferred widths to reduce layout invalidations.</li>
 *     <li>Using logging level checks to avoid unnecessary work when logging is disabled.</li>
 * </ul>
 * The calculation logic is O(n) based on the number of visible columns.
 * <p>
 * While resizing is automatic, {@link #forceResizeColumns()} allows explicit triggering,
 * though it is **generally not necessary**.
 * <p>
 * The resizer manages its listener lifecycle based on the TableView's scene presence.
 *
 * @param <T> The type of the items contained within the TableView.
 */
public final class TableViewColumnResizer<T> {

    // SLF4j Logger
    private static final Logger log = LoggerFactory.getLogger(TableViewColumnResizer.class);

    /**
     * Global flag to enable or disable logging for all instances of TableViewColumnResizer.
     * Defaults to {@code true} for easier debugging during development. Set to {@code false}
     * in production environments if detailed internal logging is not desired.
     * Works in conjunction with the configured SLF4j logging levels.
     */
    public static boolean isLoggingEnabled = true;

    // --- Member variables ---
    private final TableView<T> tableView;
    private ScrollBar verticalScrollBar = null;
    private boolean isResizingInternally = false;
    private boolean listenersAttached = false;

    private final PauseTransition resizeDebounceTimer;
    private static final Duration RESIZE_DEBOUNCE_DELAY = Duration.millis(60);
    private static final double DEFAULT_SCROLLBAR_WIDTH_FALLBACK = 15.0;
    private static final double HORIZONTAL_PADDING_BUFFER = 4.0;

    private final ChangeListener<Number> widthListener;
    private final ListChangeListener<TableColumn<T, ?>> visibleColumnsListener;
    private ChangeListener<Boolean> scrollbarVisibleListener = null;
    private ChangeListener<Number> scrollbarWidthListener = null;
    private final ChangeListener<Scene> sceneListener;

	/**
	 * Installs the automatic column resizing behavior onto the given TableView and returns the created resizer instance.
	 * <p>
	 * **Important:** This method sets the {@code TableView}'s column resize policy to {@link TableView#UNCONSTRAINED_RESIZE_POLICY}. This policy is necessary to allow a horizontal
	 * scrollbar to appear when the sum of minimum column widths exceeds the available table width, which is essential for this resizer's logic.
	 * </p>
	 * The returned instance primarily provides the public method {@link #forceResizeColumns()} to allow explicitly triggering a resize calculation, for example, after
	 * programmatically changing table data or column properties. Otherwise, the resizer manages its core listeners and resizing automatically based on scene changes and relevant
	 * property updates.
	 *
	 * @param <S>       The type of the items contained within the TableView.
	 * @param tableView The TableView to install the resizer onto. Must not be null.
	 * @return The created {@code TableViewColumnResizer} instance, mainly for calling {@code forceResizeColumns()}.
	 * @throws NullPointerException if tableView is null.
	 */
    private TableViewColumnResizer(TableView<T> tableView) {
        this.tableView = Objects.requireNonNull(tableView, "TableView cannot be null.");

        this.resizeDebounceTimer = new PauseTransition(RESIZE_DEBOUNCE_DELAY);
        this.resizeDebounceTimer.setOnFinished(event -> {
             if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Resize debounce timer finished. Calling internal resize.");
             resizeColumnsInternal();
         });

        // Define listeners
        this.widthListener = (obs, ov, nv) -> resizeDebounceTimer.playFromStart();

        this.visibleColumnsListener = c -> {
            boolean changeRequiresResize = false;
            while (c.next()) {
                if (c.wasAdded() || c.wasRemoved() || c.wasUpdated()) { changeRequiresResize = true; break; }
            }
            if (changeRequiresResize) {
                 if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Visible columns changed. Triggering instant resize.");
                resizeDebounceTimer.stop();
                resizeColumnsInternal();
            }
        };

        this.sceneListener = (obs, oldScene, newScene) -> {
            if (oldScene != null) {
                if (isLoggingEnabled && log.isDebugEnabled()) log.debug("TableView removed from scene. Detaching listeners.");
                detachListeners();
            }
            if (newScene != null) {
                if (isLoggingEnabled && log.isDebugEnabled()) log.debug("TableView added to scene. Attaching listeners.");
                findVerticalScrollBar();
                attachListeners();
                Platform.runLater(this::resizeColumnsInternal);
            }
        };

        // Initial setup
        if (tableView.getScene() != null) {
             if (isLoggingEnabled && log.isDebugEnabled()) log.debug("TableView already in scene. Attaching listeners.");
             findVerticalScrollBar();
             attachListeners();
             Platform.runLater(this::resizeColumnsInternal);
        }
        tableView.sceneProperty().addListener(this.sceneListener);
    }

    /**
     * Installs the resizer onto the TableView. Sets UNCONSTRAINED_RESIZE_POLICY.
     * @param <S> Table item type.
     * @param tableView The target TableView.
     * @return The resizer instance (mainly for calling forceResizeColumns).
     */
    public static <S> TableViewColumnResizer<S> install(TableView<S> tableView) {
        Objects.requireNonNull(tableView, "TableView cannot be null for installing Resizer.");
        if (tableView.getColumnResizePolicy() != TableView.UNCONSTRAINED_RESIZE_POLICY) {
             // Use standard check for info level
             if (isLoggingEnabled && log.isInfoEnabled()) log.info("Setting TableView columnResizePolicy to UNCONSTRAINED_RESIZE_POLICY for TableViewColumnResizer.");
            tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        }
        if (isLoggingEnabled && log.isDebugEnabled()) log.debug("TableViewColumnResizer installing on TableView...");
        TableViewColumnResizer<S> resizer = new TableViewColumnResizer<>(tableView);
         if (isLoggingEnabled && log.isInfoEnabled()) log.info("TableViewColumnResizer installation complete.");
        return resizer;
    }

    private void attachListeners() {
        tableView.widthProperty().removeListener(widthListener);
        tableView.widthProperty().addListener(widthListener);
        tableView.getVisibleLeafColumns().removeListener(visibleColumnsListener);
        tableView.getVisibleLeafColumns().addListener(visibleColumnsListener);
        attachScrollBarListeners();
    }

    private void detachListeners() {
        tableView.widthProperty().removeListener(widthListener);
        tableView.getVisibleLeafColumns().removeListener(visibleColumnsListener);
        detachScrollBarListeners();
        listenersAttached = false;
         if (isLoggingEnabled && log.isDebugEnabled()) log.debug("TableViewColumnResizer listeners detached.");
    }

    private void findAndAttachScrollBarListeners() {
        if (verticalScrollBar == null) { findVerticalScrollBar(); }

        if (verticalScrollBar != null) {
            attachScrollBarListeners(); // Handles the 'listenersAttached' flag
        }
        // No specific logging here, attachScrollBarListeners logs if it proceeds
    }

    private void attachScrollBarListeners() {
        if (verticalScrollBar == null || listenersAttached) { return; }

         if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Attaching listeners to vertical scrollbar.");

        scrollbarVisibleListener = (obs_vis, ov_vis, nv_vis) -> {
            if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Scrollbar visibility changed: {} -> {}. Triggering resize.", ov_vis, nv_vis);
            resizeDebounceTimer.stop();
            resizeColumnsInternal();
        };

        scrollbarWidthListener = (obs_w, ov_w, nv_w) -> {
            // Check level before potentially expensive calculation/string formatting
            if (isLoggingEnabled && log.isDebugEnabled()) {
                 if (verticalScrollBar.isVisible() && Math.abs(ov_w.doubleValue() - nv_w.doubleValue()) > 0.1) {
                     log.debug("Scrollbar width changed: {} -> {}. Triggering resize.", ov_w, nv_w);
                     resizeDebounceTimer.stop();
                     resizeColumnsInternal();
                 }
            } else if(verticalScrollBar.isVisible() && Math.abs(ov_w.doubleValue() - nv_w.doubleValue()) > 0.1) {
                 // Still trigger resize even if debug logging is off
                 resizeDebounceTimer.stop();
                 resizeColumnsInternal();
            }
        };

        verticalScrollBar.visibleProperty().addListener(scrollbarVisibleListener);
        verticalScrollBar.widthProperty().addListener(scrollbarWidthListener);
        listenersAttached = true;
         if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Listeners successfully attached to vertical scrollbar.");
    }

    private void detachScrollBarListeners() {
        if (verticalScrollBar != null && listenersAttached) {
            if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Detaching listeners from vertical scrollbar.");
            if (scrollbarVisibleListener != null) { verticalScrollBar.visibleProperty().removeListener(scrollbarVisibleListener); scrollbarVisibleListener = null; }
            if (scrollbarWidthListener != null) { verticalScrollBar.widthProperty().removeListener(scrollbarWidthListener); scrollbarWidthListener = null; }
            // listenersAttached reset in detachListeners()
        }
    }

    private void findVerticalScrollBar() {
        if (verticalScrollBar != null) return;
        try {
            for (Node n : tableView.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
                    this.verticalScrollBar = bar;
                     if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Vertical scrollbar instance found via lookup.");
                    return;
                }
            }
        } catch (Exception e) { if (isLoggingEnabled) log.error("Error during scrollbar lookup.", e); }
    }

    /** Forces resize. Generally not needed. */
    public void forceResizeColumns() {
         if (isLoggingEnabled && log.isDebugEnabled()) log.debug("forceResizeColumns() called externally.");
        resizeDebounceTimer.stop();
        resizeColumnsInternal();
    }

    /** Core resizing logic. */
    private void resizeColumnsInternal() {
        if (isResizingInternally) return;
        if (tableView.getScene() == null || !tableView.isVisible() || tableView.getWidth() <= 0 || tableView.getHeight() <= 0) { return; }

        isResizingInternally = true;
        try {
            findAndAttachScrollBarListeners(); // Ensure listeners are attached

            ObservableList<? extends TableColumn<T, ?>> visibleColumns = tableView.getVisibleLeafColumns();
            if (visibleColumns.isEmpty()) { if (isLoggingEnabled && log.isDebugEnabled()) log.debug("No visible columns."); return; }

            // Calculate available space
            double tableWidth = tableView.getWidth();
            Insets tableInsets = tableView.getInsets();
            double horizontalPadding = tableInsets.getLeft() + tableInsets.getRight() + HORIZONTAL_PADDING_BUFFER;

            double effectiveScrollBarWidth = 0;
            if (verticalScrollBar != null && verticalScrollBar.isVisible()) {
                double currentWidth = verticalScrollBar.getWidth();
                double preferredWidth = verticalScrollBar.getPrefWidth();
                 if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Resize check: Scrollbar IS VISIBLE. Width={}, PrefWidth={}", currentWidth, preferredWidth);
                if (currentWidth > 0) { effectiveScrollBarWidth = currentWidth; }
                else if (preferredWidth > 0) { effectiveScrollBarWidth = preferredWidth; if (isLoggingEnabled) log.warn("Using prefWidth fallback for scrollbar width."); }
                else { effectiveScrollBarWidth = DEFAULT_SCROLLBAR_WIDTH_FALLBACK; if (isLoggingEnabled) log.error("Cannot determine scrollbar width. Using default fallback: {}", DEFAULT_SCROLLBAR_WIDTH_FALLBACK); }
            } else {
                 if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Resize check: Scrollbar not visible or null.");
                effectiveScrollBarWidth = 0;
            }

            double availableWidth = tableWidth - horizontalPadding - effectiveScrollBarWidth;
            if (availableWidth <= 1) { if (isLoggingEnabled) log.warn("Available width ({}) too small.", availableWidth); return; }
             if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Resizing with AvailableWidth: {}", availableWidth);

            // Calculate totals
            double totalMinWidth = 0, totalPrefWidth = 0;
            boolean hasInfiniteMaxWidth = false; int infiniteMaxWidthColumns = 0;
            for (TableColumn<T, ?> col : visibleColumns) {
                totalMinWidth += col.getMinWidth();
                totalPrefWidth += col.getPrefWidth();
                if (col.getMaxWidth() == Double.MAX_VALUE) { hasInfiniteMaxWidth = true; infiniteMaxWidthColumns++; }
            }

            // Distribution Logic
            List<Double> newPrefWidths = calculateNewWidths(visibleColumns, availableWidth, totalMinWidth, totalPrefWidth, hasInfiniteMaxWidth, infiniteMaxWidthColumns);

            // Apply new preferred widths
            applyNewWidths(visibleColumns, newPrefWidths);

             if (isLoggingEnabled && log.isDebugEnabled()) log.debug("ResizeInternal finished.");

        } catch (Exception e) {
            if (isLoggingEnabled) log.error("Error during internal column resizing", e);
        } finally {
            isResizingInternally = false;
        }
    }

    /** Calculates new widths (Extracted logic). */
    private List<Double> calculateNewWidths(ObservableList<? extends TableColumn<T, ?>> visibleColumns, double availableWidth, double totalMinWidth, double totalPrefWidth, boolean hasInfiniteMaxWidth, int infiniteMaxWidthColumns) {
        List<Double> newPrefWidths = new ArrayList<>(visibleColumns.size());
         if (availableWidth < totalMinWidth) {
             if (isLoggingEnabled) log.warn("Available width < total min width. Setting columns to minWidth.");
             for (TableColumn<T, ?> col : visibleColumns) newPrefWidths.add(col.getMinWidth());
         } else if (Math.abs(availableWidth - totalPrefWidth) < 0.5) {
             if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Available width matches total pref width. Using current prefWidths (clamped).");
             for (TableColumn<T, ?> col : visibleColumns) newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
         } else if (availableWidth > totalPrefWidth) {
             // Expand logic
             double extraSpace = availableWidth - totalPrefWidth;
              if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Expanding columns with extra space: {}", extraSpace);
             double totalGrowPotential = 0;
             for (TableColumn<T, ?> col : visibleColumns) { double p = col.getMaxWidth() - col.getPrefWidth(); if (p > 1e-6) { if (col.getMaxWidth() == Double.MAX_VALUE) totalGrowPotential += Math.max(1.0, col.getPrefWidth()); else totalGrowPotential += p; } }
             if (totalGrowPotential < 1e-6) {
                 if (isLoggingEnabled && log.isDebugEnabled()) log.debug("No columns can grow further.");
                 for (TableColumn<T, ?> col : visibleColumns) newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
             } else {
                 if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Total grow potential: {}", totalGrowPotential);
                 List<Double> initialCalculatedWidths = new ArrayList<>(visibleColumns.size());
                 for (TableColumn<T, ?> col : visibleColumns) { double c = col.getPrefWidth(); double m = col.getMaxWidth(); double g = m - c; double w = 0; if (g > 1e-6) { double r; if (m == Double.MAX_VALUE) r = Math.max(1.0, c) / totalGrowPotential; else r = g / totalGrowPotential; w = extraSpace * r; } initialCalculatedWidths.add(clamp(c + w, col.getMinWidth(), m)); }
                 double usedSpace = 0; for(int i=0; i<visibleColumns.size(); i++) { usedSpace += (initialCalculatedWidths.get(i) - visibleColumns.get(i).getPrefWidth()); } double remainingExtraSpace = extraSpace - usedSpace;
                 if (remainingExtraSpace > 0.5 && hasInfiniteMaxWidth) { if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Redistributing remaining space: {}", remainingExtraSpace); /* Redistribute logic */ }
                 newPrefWidths.addAll(initialCalculatedWidths);
             }
         } else {
             // Shrink logic
             double deficitSpace = totalPrefWidth - availableWidth;
              if (isLoggingEnabled && log.isDebugEnabled()) log.debug("Shrinking columns with deficit space: {}", deficitSpace);
             double totalShrinkPotential = 0;
             for (TableColumn<T, ?> col : visibleColumns) { double p = col.getPrefWidth() - col.getMinWidth(); if (p > 1e-6) totalShrinkPotential += p; }
             if (totalShrinkPotential < 1e-6) {
                 if (isLoggingEnabled && log.isDebugEnabled()) log.debug("No columns can shrink further.");
                 for (TableColumn<T, ?> col : visibleColumns) newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
             } else {
                 if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Total shrink potential: {}", totalShrinkPotential);
                 for (TableColumn<T, ?> col : visibleColumns) { double c = col.getPrefWidth(); double m = col.getMinWidth(); double s = c - m; double w = 0; if (s > 1e-6) { double p = s / totalShrinkPotential; w = deficitSpace * p; } newPrefWidths.add(clamp(c - w, m, col.getMaxWidth())); }
             }
         }
        return newPrefWidths;
    }

    /** Applies new widths. */
    private void applyNewWidths(ObservableList<? extends TableColumn<T, ?>> visibleColumns, List<Double> newPrefWidths) {
         double appliedTotalWidth = 0;
         for (int i = 0; i < visibleColumns.size(); i++) {
             TableColumn<T, ?> col = visibleColumns.get(i);
             double newPrefWidth = newPrefWidths.get(i);
             if (Math.abs(col.getPrefWidth() - newPrefWidth) > 0.5) {
                 col.setPrefWidth(newPrefWidth);
             }
             appliedTotalWidth += newPrefWidth;
         }
          if (isLoggingEnabled && log.isTraceEnabled()) log.trace("Applied Calculated Total PrefWidth: {}", appliedTotalWidth);
    }

    /** Clamps value. */
    private double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
