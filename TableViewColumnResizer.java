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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import izon.utils.logging.Log; // Replace with your logging framework if necessary
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

/**
 * Automatically resizes the columns of a JavaFX TableView to proportionally fill the available horizontal space, while respecting column minimum and maximum widths. It correctly
 * accounts for the vertical scrollbar's width when it becomes visible.
 * <p>
 * Use the static {@link #install(TableView)} method to attach this behavior to a TableView. This method automatically sets the TableView's column resize policy to
 * {@link TableView#UNCONSTRAINED_RESIZE_POLICY}, which is required for the resizer to function correctly.
 * <p>
 * The resizer operates automatically by listening to changes in the TableView's width, visible columns, and the vertical scrollbar's state. Due to JavaFX layout timing, there
 * might be a brief visual adjustment (flicker) upon initial display or when the scrollbar appears/disappears, as the resize calculation is triggered *after* the state change is
 * detected by the listeners.
 * <p>
 * **Efficiency Considerations:** Care has been taken in the implementation to balance accurate resizing with performance. Key efficiency measures include:
 * <ul>
 * <li>Debouncing reactions to rapid table width changes to avoid excessive calculations during window resizing.</li>
 * <li>Minimizing potentially expensive operations like node lookups for the scrollbar.</li>
 * <li>Applying calculated preferred widths to columns only when a significant change (> 0.5px) is detected, reducing unnecessary layout invalidations.</li>
 * </ul>
 * The calculation logic primarily involves linear traversals of the visible columns, representing an efficient approach for typical UI scenarios where the number of columns is
 * moderate.
 * <p>
 * While the resizing is typically fully automatic, a public method {@link #forceResizeColumns()} is provided. This allows for explicitly triggering a resize calculation but is
 * **generally not necessary** due to the comprehensive automatic listeners. See the method's documentation for potential use cases.
 * <p>
 * The resizer manages its own lifecycle by attaching/detaching listeners when the TableView is added to or removed from a scene to prevent memory leaks.
 *
 * @param <T> The type of the items contained within the TableView.
 */
public final class TableViewColumnResizer<T> { // Make class final if constructor is private

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
	public static <S> TableViewColumnResizer<S> install(TableView<S> tableView) {
		Objects.requireNonNull(tableView, "TableView cannot be null for installing Resizer.");

		if (tableView.getColumnResizePolicy() != TableView.UNCONSTRAINED_RESIZE_POLICY) {
			Log.info("Setting TableView columnResizePolicy to UNCONSTRAINED_RESIZE_POLICY for TableViewColumnResizer.");
			tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
		}

		Log.fine("TableViewColumnResizer installing on TableView...");
		TableViewColumnResizer<S> resizer = new TableViewColumnResizer<>(tableView);
		Log.fine("TableViewColumnResizer installation complete.");
		return resizer; // Return the created instance
	}

	private final TableView<T> tableView;
	private ScrollBar          verticalScrollBar    = null;
	private boolean            isResizingInternally = false;
	private boolean            listenersAttached    = false; // Prevents adding listeners multiple times

	// Timers and constants
	private final PauseTransition resizeDebounceTimer;
	private static final Duration RESIZE_DEBOUNCE_DELAY            = Duration.millis(60);
	private static final double   DEFAULT_SCROLLBAR_WIDTH_FALLBACK = 15.0;
	private static final double   HORIZONTAL_PADDING_BUFFER        = 4.0;

	// --- Listener instances (needed for removal) ---
	private final ChangeListener<Number>                widthListener;
	private final ListChangeListener<TableColumn<T, ?>> visibleColumnsListener;
	private ChangeListener<Boolean>                     scrollbarVisibleListener = null; // Initialized later
	private ChangeListener<Number>                      scrollbarWidthListener   = null; // Initialized later
	// Listener for scene changes to manage attachment/detachment
	private final ChangeListener<Scene> sceneListener;

	/**
	 * Private constructor to prevent direct instantiation. Use the static install method.
	 *
	 * @param tableView The TableView to manage.
	 */
	private TableViewColumnResizer(TableView<T> tableView) {
		this.tableView = Objects.requireNonNull(tableView, "TableView cannot be null.");

		// Initialize debounce timer
		this.resizeDebounceTimer = new PauseTransition(RESIZE_DEBOUNCE_DELAY);
		this.resizeDebounceTimer.setOnFinished(event -> resizeColumnsInternal());

		// --- Define listeners as member variables ---
		this.widthListener = (obs, ov, nv) -> resizeDebounceTimer.playFromStart();

		this.visibleColumnsListener = c -> {
			boolean changeRequiresResize = false;
			while (c.next()) {
				if (c.wasAdded() || c.wasRemoved() || c.wasUpdated()) {
					changeRequiresResize = true;
					break;
				}
			}
			if (changeRequiresResize) {
				Log.fine("Visible columns changed. Triggering instant resize.");
				resizeDebounceTimer.stop();
				resizeColumnsInternal();
			}
		};

		// Listener to attach/detach based on scene presence
		this.sceneListener = (obs, oldScene, newScene) -> {
			if (oldScene != null) {
				Log.fine("TableView removed from scene. Detaching listeners.");
				detachListeners();
			}
			if (newScene != null) {
				Log.fine("TableView added to scene. Attaching listeners.");
				// Attempt to find scrollbar immediately when added to scene
				findVerticalScrollBar();
				attachListeners();
				// Trigger an initial resize check shortly after being added to scene
				// Using Platform.runLater defers it slightly after scene setup
				Platform.runLater(this::resizeColumnsInternal);
			}
		};

		// --- Initial setup ---
		// Check if already in scene, if so, attach listeners immediately
		if (tableView.getScene() != null) {
			Log.fine("TableView already in scene during construction. Attaching listeners.");
			findVerticalScrollBar(); // Find scrollbar now
			attachListeners();
			// Trigger initial resize check deferred
			Platform.runLater(this::resizeColumnsInternal);
		}
		// Always add the scene listener to handle future changes
		tableView.sceneProperty().addListener(this.sceneListener);
	}

	/**
	 * Attaches the necessary listeners to the TableView and its potential ScrollBar. Ensures listeners are only attached once.
	 */
	private void attachListeners() {
		// Attach listeners to TableView if not already attached via scene listener logic
		// (Technically redundant if scene listener logic works perfectly, but safe)
		tableView.widthProperty().removeListener(widthListener); // Remove first to be safe
		tableView.widthProperty().addListener(widthListener);

		tableView.getVisibleLeafColumns().removeListener(visibleColumnsListener); // Remove first
		tableView.getVisibleLeafColumns().addListener(visibleColumnsListener);

		// Try to attach scrollbar listeners
		attachScrollBarListeners();
	}

	/**
	 * Detaches listeners from the TableView and ScrollBar to prevent memory leaks when the TableView is removed from the scene.
	 */
	private void detachListeners() {
		tableView.widthProperty().removeListener(widthListener);
		tableView.getVisibleLeafColumns().removeListener(visibleColumnsListener);
		detachScrollBarListeners();
		listenersAttached = false; // Reset scrollbar listener flag
		Log.fine("TableViewColumnResizer listeners detached.");
	}

	/**
	 * Finds the vertical scrollbar and attaches listeners if found and not already attached. This might be called multiple times (e.g., initially, after layout pass).
	 */
	private void findAndAttachScrollBarListeners() {
		if (verticalScrollBar == null) {
			findVerticalScrollBar();
		}
		if (verticalScrollBar != null) {
			attachScrollBarListeners(); // This method now handles the 'listenersAttached' flag
		} else {
			// If not found now, the needsLayout listener (if added) might try later
			// Or rely on the next resizeColumnsInternal call to try findVerticalScrollBar again.
		}
	}

	/**
	 * Attaches listeners to the found vertical scrollbar, if not already attached.
	 */
	private void attachScrollBarListeners() {
		if (verticalScrollBar == null || listenersAttached) {
			return; // Nothing to attach to or already attached
		}

		Log.fine("Attaching listeners to vertical scrollbar.");

		// Define scrollbar listeners here (or use pre-defined fields if preferred)
		scrollbarVisibleListener = (obs_vis, ov_vis, nv_vis) -> {
			Log.fine("Scrollbar visibility changed: " + ov_vis + " -> " + nv_vis + ". Triggering resize.");
			resizeDebounceTimer.stop();
			resizeColumnsInternal();
		};

		scrollbarWidthListener = (obs_w, ov_w, nv_w) -> {
			if (verticalScrollBar.isVisible() && Math.abs(ov_w.doubleValue() - nv_w.doubleValue()) > 0.1) {
				Log.fine("Scrollbar width changed: " + ov_w + " -> " + nv_w + ". Triggering resize.");
				resizeDebounceTimer.stop();
				resizeColumnsInternal();
			}
		};

		// Add the listeners
		verticalScrollBar.visibleProperty().addListener(scrollbarVisibleListener);
		verticalScrollBar.widthProperty().addListener(scrollbarWidthListener);

		listenersAttached = true; // Mark as attached
	}

	/**
	 * Detaches listeners from the vertical scrollbar, if they were previously attached.
	 */
	private void detachScrollBarListeners() {
		if (verticalScrollBar != null && listenersAttached) {
			Log.fine("Detaching listeners from vertical scrollbar.");
			if (scrollbarVisibleListener != null) {
				verticalScrollBar.visibleProperty().removeListener(scrollbarVisibleListener);
			}
			if (scrollbarWidthListener != null) {
				verticalScrollBar.widthProperty().removeListener(scrollbarWidthListener);
			}
			// Reset for potential re-attachment later if scene changes
			scrollbarVisibleListener = null;
			scrollbarWidthListener = null;
			// listenersAttached flag is reset in detachListeners()
		}
	}

	/** Performs the lookup for the vertical scrollbar node. */
	private void findVerticalScrollBar() {
		if (verticalScrollBar != null)
			return;
		try {
			for (Node n : tableView.lookupAll(".scroll-bar")) {
				if (n instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) {
					this.verticalScrollBar = bar;
					Log.fine("Vertical scrollbar instance found via lookup.");
					return;
				}
			}
		} catch (Exception e) {
			Log.error(e, "Error during scrollbar lookup.");
		}
	}

	/** The core column resizing logic. */
	private void resizeColumnsInternal() {
		if (isResizingInternally)
			return;
		// Check scene presence as an extra guard, though listener detachment should handle it
		if (tableView.getScene() == null || !tableView.isVisible() || tableView.getWidth() <= 0 || tableView.getHeight() <= 0) {
			// Log.fine("Resize skipped: Table not ready or not in scene.");
			return;
		}

		isResizingInternally = true;
		try {
			// Ensure scrollbar listeners are attached if possible (might have appeared late)
			findAndAttachScrollBarListeners(); // Attempt to find/attach if not done yet

			ObservableList<? extends TableColumn<T, ?>> visibleColumns = tableView.getVisibleLeafColumns();
			if (visibleColumns.isEmpty()) {
				Log.fine("No visible columns.");
				return;
			}

			// --- Calculate available space ---
			double tableWidth = tableView.getWidth();
			Insets tableInsets = tableView.getInsets();
			double horizontalPadding = tableInsets.getLeft() + tableInsets.getRight() + HORIZONTAL_PADDING_BUFFER;

			double effectiveScrollBarWidth = 0;
			// We rely on findVerticalScrollBar having been called before or at the start of this method
			if (verticalScrollBar != null && verticalScrollBar.isVisible()) {
				double currentWidth = verticalScrollBar.getWidth();
				double preferredWidth = verticalScrollBar.getPrefWidth();
				Log.fine("Resize check: Scrollbar IS VISIBLE. Width=" + currentWidth + ", PrefWidth=" + preferredWidth);
				if (currentWidth > 0) {
					effectiveScrollBarWidth = currentWidth;
				} else if (preferredWidth > 0) {
					effectiveScrollBarWidth = preferredWidth;
					Log.warn("Using prefWidth fallback.");
				} else {
					effectiveScrollBarWidth = DEFAULT_SCROLLBAR_WIDTH_FALLBACK;
					Log.error("Using default fallback width.");
				}
			} else {
				Log.fine("Resize check: Scrollbar not visible or null.");
				effectiveScrollBarWidth = 0;
			}

			double availableWidth = tableWidth - horizontalPadding - effectiveScrollBarWidth;
			if (availableWidth <= 1) {
				Log.warn("Available width too small.");
				return;
			}
			Log.fine("Resizing with AvailableWidth: " + availableWidth);

			// --- Calculate total widths ---
			double totalMinWidth = 0, totalPrefWidth = 0;
			boolean hasInfiniteMaxWidth = false;
			int infiniteMaxWidthColumns = 0;
			for (TableColumn<T, ?> col : visibleColumns) {
				totalMinWidth += col.getMinWidth();
				totalPrefWidth += col.getPrefWidth();
				if (col.getMaxWidth() == Double.MAX_VALUE) {
					hasInfiniteMaxWidth = true;
					infiniteMaxWidthColumns++;
				}
			}

			// --- Distribution Logic (Condensed - Same logic as before) ---
			List<Double> newPrefWidths = calculateNewWidths(visibleColumns, availableWidth, totalMinWidth, totalPrefWidth, hasInfiniteMaxWidth, infiniteMaxWidthColumns);

			// --- Apply new preferred widths ---
			applyNewWidths(visibleColumns, newPrefWidths);

			Log.fine("ResizeInternal finished.");

		} catch (Exception e) {
			Log.error(e, "Error during internal column resizing");
		} finally {
			isResizingInternally = false;
		}
	}

	/**
	 * Calculates the new preferred widths for the visible columns based on available space. (Extracted distribution logic for clarity - kept same logic as previous working
	 * version).
	 * 
	 * @return A list containing the new preferred width for each visible column.
	 */
	private List<Double> calculateNewWidths(ObservableList<? extends TableColumn<T, ?>> visibleColumns, double availableWidth, double totalMinWidth, double totalPrefWidth,
	        boolean hasInfiniteMaxWidth, int infiniteMaxWidthColumns) {
		List<Double> newPrefWidths = new ArrayList<>(visibleColumns.size());

		if (availableWidth < totalMinWidth) {
			Log.warn("Available width < total min width. Setting columns to minWidth.");
			for (TableColumn<T, ?> col : visibleColumns)
				newPrefWidths.add(col.getMinWidth());
		} else if (Math.abs(availableWidth - totalPrefWidth) < 0.5) {
			Log.fine("Available width matches total pref width. Using current prefWidths (clamped).");
			for (TableColumn<T, ?> col : visibleColumns)
				newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
		} else if (availableWidth > totalPrefWidth) {
			// Expand logic (same as before)
			double extraSpace = availableWidth - totalPrefWidth;
			double totalGrowPotential = 0;
			for (TableColumn<T, ?> col : visibleColumns) { /* Calculate totalGrowPotential */
				double potential = col.getMaxWidth() - col.getPrefWidth();
				if (potential > 1e-6) {
					if (col.getMaxWidth() == Double.MAX_VALUE)
						totalGrowPotential += Math.max(1.0, col.getPrefWidth());
					else
						totalGrowPotential += potential;
				}
			}
			if (totalGrowPotential < 1e-6) { // No growth possible
				for (TableColumn<T, ?> col : visibleColumns)
					newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
			} else { // Distribute extra space
				List<Double> initialCalculatedWidths = new ArrayList<>(visibleColumns.size());
				// First pass distribution
				for (TableColumn<T, ?> col : visibleColumns) { /* Calculate initial widthToAdd and clamp */
					double currentPref = col.getPrefWidth();
					double colMaxWidth = col.getMaxWidth();
					double growPotential = colMaxWidth - currentPref;
					double widthToAdd = 0;
					if (growPotential > 1e-6) {
						double colPotentialRatio;
						if (colMaxWidth == Double.MAX_VALUE)
							colPotentialRatio = Math.max(1.0, currentPref) / totalGrowPotential;
						else
							colPotentialRatio = growPotential / totalGrowPotential;
						widthToAdd = extraSpace * colPotentialRatio;
					}
					initialCalculatedWidths.add(clamp(currentPref + widthToAdd, col.getMinWidth(), colMaxWidth));
				}
				// Second pass redistribution (same logic as before)
				double usedSpace = 0;
				for (int i = 0; i < visibleColumns.size(); i++) {
					usedSpace += (initialCalculatedWidths.get(i) - visibleColumns.get(i).getPrefWidth());
				}
				double remainingExtraSpace = extraSpace - usedSpace;
				if (remainingExtraSpace > 0.5 && hasInfiniteMaxWidth) { /* Redistribute remaining */
					double infiniteTotalInitialWidth = 0;
					for (int i = 0; i < visibleColumns.size(); i++)
						if (visibleColumns.get(i).getMaxWidth() == Double.MAX_VALUE)
							infiniteTotalInitialWidth += initialCalculatedWidths.get(i);
					if (infiniteTotalInitialWidth > 0) {
						/* Proportionally */ for (int i = 0; i < visibleColumns.size(); i++)
							if (visibleColumns.get(i).getMaxWidth() == Double.MAX_VALUE) {
								double c = initialCalculatedWidths.get(i);
								double p = c / infiniteTotalInitialWidth;
								initialCalculatedWidths.set(i, clamp(c + (remainingExtraSpace * p), visibleColumns.get(i).getMinWidth(), Double.MAX_VALUE));
							}
					} else if (infiniteMaxWidthColumns > 0) {
						/* Equally */ double s = remainingExtraSpace / infiniteMaxWidthColumns;
						for (int i = 0; i < visibleColumns.size(); i++)
							if (visibleColumns.get(i).getMaxWidth() == Double.MAX_VALUE)
								initialCalculatedWidths.set(i, clamp(initialCalculatedWidths.get(i) + s, visibleColumns.get(i).getMinWidth(), Double.MAX_VALUE));
					}
				}
				newPrefWidths.addAll(initialCalculatedWidths);
			}
		} else {
			// Shrink logic (same as before)
			double deficitSpace = totalPrefWidth - availableWidth;
			double totalShrinkPotential = 0;
			for (TableColumn<T, ?> col : visibleColumns) {
				/* Calculate totalShrinkPotential */ double p = col.getPrefWidth() - col.getMinWidth();
				if (p > 1e-6)
					totalShrinkPotential += p;
			}
			if (totalShrinkPotential < 1e-6) { // No shrink possible
				for (TableColumn<T, ?> col : visibleColumns)
					newPrefWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
			} else { // Distribute deficit
				for (TableColumn<T, ?> col : visibleColumns) {
					/* Calculate widthToRemove and clamp */ double currentPref = col.getPrefWidth();
					double colMinWidth = col.getMinWidth();
					double shrinkPotential = currentPref - colMinWidth;
					double widthToRemove = 0;
					if (shrinkPotential > 1e-6) {
						double proportion = shrinkPotential / totalShrinkPotential;
						widthToRemove = deficitSpace * proportion;
					}
					newPrefWidths.add(clamp(currentPref - widthToRemove, colMinWidth, col.getMaxWidth()));
				}
			}
		}
		return newPrefWidths;
	}

	/**
	 * Applies the calculated preferred widths to the visible columns.
	 */
	private void applyNewWidths(ObservableList<? extends TableColumn<T, ?>> visibleColumns, List<Double> newPrefWidths) {
		double appliedTotalWidth = 0; // For logging/debugging
		for (int i = 0; i < visibleColumns.size(); i++) {
			TableColumn<T, ?> col = visibleColumns.get(i);
			double newPrefWidth = newPrefWidths.get(i);
			// Apply width only if it has changed significantly
			if (Math.abs(col.getPrefWidth() - newPrefWidth) > 0.5) {
				col.setPrefWidth(newPrefWidth);
			}
			appliedTotalWidth += newPrefWidth;
		}
		Log.fine("Applied Calculated Total PrefWidth: " + appliedTotalWidth);
	}

	/**
	 * Forces an immediate recalculation and application of column widths. This stops any pending debounced resize operations from the table width listener. Useful if an external
	 * event (e.g., programmatically changing column visibility or after manually setting table items) requires an immediate layout update.
	 */
	public void forceResizeColumns() {
		Log.fine("forceResizeColumns() called externally.");
		// Stop any pending actions from the width debounce timer
		resizeDebounceTimer.stop();
		// Execute the resize logic immediately
		// It will internally check visibility, scrollbar state etc. at this moment.
		resizeColumnsInternal();
	}

	/** Clamps value. */
	private double clamp(double value, double min, double max) {
		if (value < min)
			return min;
		if (value > max)
			return max;
		return value;
	}
}
