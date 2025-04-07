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

package izon.framework.tableview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// SLF4J imports
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

/**
 * Automatically resizes the columns of a JavaFX TableView to proportionally fill the available horizontal space, while respecting column minimum and maximum widths. It correctly
 * accounts for the vertical scrollbar's width when it becomes visible. This implementation uses SLF4J for logging.
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
public final class TableViewColumnResizer<T> {

	// SLF4J Logger instance
	private static final Logger log = LoggerFactory.getLogger(TableViewColumnResizer.class);

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
			log.info("Setting TableView columnResizePolicy to UNCONSTRAINED_RESIZE_POLICY for TableViewColumnResizer.");
			tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
		}

		log.debug("TableViewColumnResizer installing on TableView..."); // Changed from fine to debug
		TableViewColumnResizer<S> resizer = new TableViewColumnResizer<>(tableView);
		log.debug("TableViewColumnResizer installation complete."); // Changed from fine to debug
		return resizer; // Return the created instance
	}

	// Removed: public static boolean isLoggingEnabled = true; (Control via SLF4J config)

	private final TableView<T> tableView;
	private ScrollBar          verticalScrollBar    = null;
	private boolean            isResizingInternally = false;
	private boolean            listenersAttached    = false; // Prevents adding listeners multiple times

	// Timers and constants
	private final PauseTransition resizeDebounceTimer;
	private static final Duration RESIZE_DEBOUNCE_DELAY            = Duration.millis(60);
	private static final double   DEFAULT_SCROLLBAR_WIDTH_FALLBACK = 15.0;
	private static final double   HORIZONTAL_PADDING_BUFFER        = 3;

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
				log.debug("Visible columns changed. Triggering instant resize."); // fine -> debug
				resizeDebounceTimer.stop();
				resizeColumnsInternal();
			}
		};

		// Listener to attach/detach based on scene presence
		this.sceneListener = (obs, oldScene, newScene) -> {
			if (oldScene != null) {
				log.debug("TableView removed from scene. Detaching listeners."); // fine -> debug
				detachListeners();
			}
			if (newScene != null) {
				log.debug("TableView added to scene. Attaching listeners."); // fine -> debug
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
			log.debug("TableView already in scene during construction. Attaching listeners."); // fine -> debug
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
		log.debug("TableViewColumnResizer listeners detached."); // fine -> debug
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

		log.debug("Attaching listeners to vertical scrollbar."); // fine -> debug

		// Define scrollbar listeners here (or use pre-defined fields if preferred)
		scrollbarVisibleListener = (obs_vis, ov_vis, nv_vis) -> {
			log.debug("Scrollbar visibility changed: {} -> {}. Triggering resize.", ov_vis, nv_vis); // fine -> debug, use {}
			resizeDebounceTimer.stop();
			resizeColumnsInternal();
		};

		scrollbarWidthListener = (obs_w, ov_w, nv_w) -> {
			if (verticalScrollBar.isVisible() && Math.abs(ov_w.doubleValue() - nv_w.doubleValue()) > 0.1) {
				log.debug("Scrollbar width changed: {} -> {}. Triggering resize.", ov_w, nv_w); // fine -> debug, use {}
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
			log.debug("Detaching listeners from vertical scrollbar."); // fine -> debug
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
					log.debug("Vertical scrollbar instance found via lookup."); // fine -> debug
					return;
				}
			}
		} catch (Exception e) {
			// Log the exception itself
			log.error("Error during scrollbar lookup.", e);
		}
	}

	/** Core resizing logic. */
	private void resizeColumnsInternal() {
		if (isResizingInternally)
			return;
		if (tableView.getScene() == null || !tableView.isVisible() || tableView.getWidth() <= 0 || tableView.getHeight() <= 0)
			return;

		isResizingInternally = true;
		try {
			findAndAttachScrollBarListeners();

			ObservableList<? extends TableColumn<T, ?>> visibleColumns = tableView.getVisibleLeafColumns();
			if (visibleColumns.isEmpty()) {
				log.debug("No visible columns."); // fine -> debug
				return;
			}

			// Calculate available space
			double tableWidth = tableView.getWidth();
			Insets tableInsets = tableView.getInsets();
			double horizontalPadding = tableInsets.getLeft() + tableInsets.getRight() + HORIZONTAL_PADDING_BUFFER;
			double effectiveScrollBarWidth = 0;
			if (verticalScrollBar != null && verticalScrollBar.isVisible()) {
				double currentWidth = verticalScrollBar.getWidth();
				double preferredWidth = verticalScrollBar.getPrefWidth();
				log.debug("Resize check: Scrollbar IS VISIBLE. Width={}, PrefWidth={}", currentWidth, preferredWidth); // fine -> debug, use {}
				if (currentWidth > 0) {
					effectiveScrollBarWidth = currentWidth;
				} else if (preferredWidth > 0) {
					effectiveScrollBarWidth = preferredWidth;
					log.warn("Using prefWidth fallback for scrollbar width."); // Keep warn
				} else {
					effectiveScrollBarWidth = DEFAULT_SCROLLBAR_WIDTH_FALLBACK;
					log.warn("Cannot determine scrollbar width. Using default fallback: {}", DEFAULT_SCROLLBAR_WIDTH_FALLBACK); // Keep warn, use {}
				}
			} else {
				log.debug("Resize check: Scrollbar not visible or null."); // fine -> debug
				effectiveScrollBarWidth = 0;
			}

			double availableWidth = tableWidth - horizontalPadding - effectiveScrollBarWidth;
			if (availableWidth <= 1) {
				log.warn("Available width ({}) too small.", availableWidth); // Keep warn, use {}
				return;
			}

			log.debug("Resizing with AvailableWidth: {}", availableWidth); // fine -> debug, use {}

			// Use the calculation method with integer rounding
			List<Double> newPrefWidths = calculateNewWidthsAsIntegers(visibleColumns, availableWidth);

			// Apply the calculated widths
			applyNewWidths(visibleColumns, newPrefWidths);

			log.debug("ResizeInternal finished."); // fine -> debug

		} catch (Exception e) {
			// Log the error message and potentially the exception if needed for more detail elsewhere
			log.warn("Error during internal column resizing: {}", e.getMessage()); // Keep warn, use {}
            // For more detail, you could use: log.warn("Error during internal column resizing.", e);
		} finally {
			isResizingInternally = false;
		}
	}

	/**
	 * Calculates the ideal proportional preferred widths as doubles based on available space and column constraints. This logic is used as input for the integer rounding method.
	 *
	 * @param visibleColumns          The list of columns currently visible.
	 * @param availableWidth          The total horizontal space available for all columns.
	 * @param totalMinWidth           The sum of minimum widths of all visible columns.
	 * @param totalPrefWidth          The sum of preferred widths of all visible columns.
	 * @param hasInfiniteMaxWidth     Flag indicating if at least one column has Double.MAX_VALUE maxWidth.
	 * @param infiniteMaxWidthColumns Count of columns with Double.MAX_VALUE maxWidth.
	 * @return A list containing the calculated ideal preferred width (as double) for each visible column.
	 */
	private List<Double> calculateProportionalWidths(ObservableList<? extends TableColumn<T, ?>> visibleColumns, double availableWidth, double totalMinWidth, double totalPrefWidth,
	        boolean hasInfiniteMaxWidth, int infiniteMaxWidthColumns) {
		List<Double> idealWidths = new ArrayList<>(visibleColumns.size());
		int columnCount = visibleColumns.size();
		if (columnCount == 0)
			return idealWidths; // Return empty list if no columns

		// --- Distribution Logic ---
		if (availableWidth < totalMinWidth) {
			// Case 1: Not enough space for min widths
			// Note: Logging moved to the calling method (calculateNewWidthsAsIntegers)
			for (TableColumn<T, ?> col : visibleColumns) {
				idealWidths.add(col.getMinWidth());
			}
		} else if (Math.abs(availableWidth - totalPrefWidth) < 0.5) {
			// Case 2: Perfect fit (or close enough)
			// Note: Logging moved to the calling method
			for (TableColumn<T, ?> col : visibleColumns) {
				idealWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
			}
		} else if (availableWidth > totalPrefWidth) {
			// Case 3: Expand columns
			double extraSpace = availableWidth - totalPrefWidth;
			double totalGrowPotential = 0;
			// Calculate total grow potential
			for (TableColumn<T, ?> col : visibleColumns) {
				double potential = col.getMaxWidth() - col.getPrefWidth();
				if (potential > 1e-6) { // Can this column grow?
					if (col.getMaxWidth() == Double.MAX_VALUE) {
						totalGrowPotential += Math.max(1.0, col.getPrefWidth()); // Base potential on prefWidth for infinite
					} else {
						totalGrowPotential += potential;
					}
				}
			}

			if (totalGrowPotential < 1e-6) { // No growth possible
				// Note: Logging moved to the calling method
				for (TableColumn<T, ?> col : visibleColumns) {
					idealWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
				}
			} else { // Distribute extra space
				// Note: Logging moved to the calling method
				List<Double> initialCalculatedWidths = new ArrayList<>(columnCount);
				// First pass distribution
				for (TableColumn<T, ?> col : visibleColumns) {
					double currentPref = col.getPrefWidth();
					double colMaxWidth = col.getMaxWidth();
					double growPotential = colMaxWidth - currentPref;
					double widthToAdd = 0;
					if (growPotential > 1e-6) {
						double colPotentialRatio;
						if (colMaxWidth == Double.MAX_VALUE) {
							colPotentialRatio = Math.max(1.0, currentPref) / totalGrowPotential;
						} else {
							colPotentialRatio = growPotential / totalGrowPotential;
						}
						widthToAdd = extraSpace * colPotentialRatio;
					}
					initialCalculatedWidths.add(clamp(currentPref + widthToAdd, col.getMinWidth(), colMaxWidth));
				}
				// Second pass redistribution for remaining space after clamping
				double usedSpace = 0;
				for (int i = 0; i < columnCount; i++) {
					// Correct calculation: Difference between initial calculated and original preferred width
                    usedSpace += Math.max(0, initialCalculatedWidths.get(i) - visibleColumns.get(i).getPrefWidth());
                }

                // Calculate remaining extra space more accurately
				double distributedSpace = 0;
                for (int i = 0; i < columnCount; i++) {
                    distributedSpace += initialCalculatedWidths.get(i);
                }
				double remainingExtraSpace = availableWidth - distributedSpace; // Compare allocated space with target

				// Redistribute only significant positive remaining space
				if (remainingExtraSpace > 0.5 && hasInfiniteMaxWidth) {
				    log.debug("Redistributing remaining {} px among infinite columns.", String.format("%.2f", remainingExtraSpace)); // fine -> debug
					double infiniteTotalInitialWidth = 0;
					for (int i = 0; i < columnCount; i++) {
						if (visibleColumns.get(i).getMaxWidth() == Double.MAX_VALUE && initialCalculatedWidths.get(i) < Double.MAX_VALUE) { // Check it wasn't clamped to max already
                            infiniteTotalInitialWidth += initialCalculatedWidths.get(i);
						}
					}
					if (infiniteTotalInitialWidth > 0) { // Distribute proportionally
						for (int i = 0; i < columnCount; i++) {
							TableColumn<T, ?> col = visibleColumns.get(i);
							// Check if it's an infinite column and wasn't already clamped
							if (col.getMaxWidth() == Double.MAX_VALUE && initialCalculatedWidths.get(i) < Double.MAX_VALUE) {
								double c = initialCalculatedWidths.get(i);
								double p = c / infiniteTotalInitialWidth;
								initialCalculatedWidths.set(i, clamp(c + (remainingExtraSpace * p), col.getMinWidth(), Double.MAX_VALUE));
							}
						}
					} else if (infiniteMaxWidthColumns > 0) { // Distribute equally (fallback) if proportional base is zero
                        int eligibleInfiniteColumns = 0;
                        for (int i = 0; i < columnCount; i++) {
                            if (visibleColumns.get(i).getMaxWidth() == Double.MAX_VALUE && initialCalculatedWidths.get(i) < Double.MAX_VALUE) {
                                eligibleInfiniteColumns++;
                            }
                        }
                        if (eligibleInfiniteColumns > 0) {
                            double s = remainingExtraSpace / eligibleInfiniteColumns;
                            for (int i = 0; i < columnCount; i++) {
                                TableColumn<T, ?> col = visibleColumns.get(i);
                                if (col.getMaxWidth() == Double.MAX_VALUE && initialCalculatedWidths.get(i) < Double.MAX_VALUE) {
                                    initialCalculatedWidths.set(i, clamp(initialCalculatedWidths.get(i) + s, col.getMinWidth(), Double.MAX_VALUE));
                                }
                            }
                        }
					}
				}
				// Assign results to idealWidths list
				idealWidths.addAll(initialCalculatedWidths);
			}
		} else {
			// Case 4: Shrink columns
			double deficitSpace = totalPrefWidth - availableWidth;
			double totalShrinkPotential = 0;
			// Calculate total shrink potential
			for (TableColumn<T, ?> col : visibleColumns) {
				double potential = col.getPrefWidth() - col.getMinWidth();
				if (potential > 1e-6) { // Can this column shrink?
					totalShrinkPotential += potential;
				}
			}

			if (totalShrinkPotential < 1e-6) { // No shrink possible
				// Note: Logging moved to the calling method
				for (TableColumn<T, ?> col : visibleColumns) {
					idealWidths.add(clamp(col.getPrefWidth(), col.getMinWidth(), col.getMaxWidth()));
				}
			} else { // Distribute deficit
				// Note: Logging moved to the calling method
				for (TableColumn<T, ?> col : visibleColumns) {
					double currentPref = col.getPrefWidth();
					double colMinWidth = col.getMinWidth();
					double shrinkPotential = currentPref - colMinWidth;
					double widthToRemove = 0;
					if (shrinkPotential > 1e-6) {
						double proportion = shrinkPotential / totalShrinkPotential;
						widthToRemove = deficitSpace * proportion;
					}
					idealWidths.add(clamp(currentPref - widthToRemove, colMinWidth, col.getMaxWidth()));
				}
			}
		}
		return idealWidths; // Return the list of calculated ideal double widths
	}

	/** Calculates widths rounding to integers, adjusting last column. */
	private List<Double> calculateNewWidthsAsIntegers(ObservableList<? extends TableColumn<T, ?>> visibleColumns, double availableWidth) {
		int columnCount = visibleColumns.size();
		List<Double> finalWidths = new ArrayList<>(Collections.nCopies(columnCount, 0.0));
		if (columnCount == 0)
			return finalWidths;

		// Calculate ideal double widths first
		double totalMinWidth = 0, totalPrefWidth = 0;
		boolean hasInfiniteMaxWidth = false;
		int infiniteMaxWidthColumns = 0;
		for (TableColumn<T, ?> col : visibleColumns) { /* Calculate totals */
			totalMinWidth += col.getMinWidth();
			totalPrefWidth += col.getPrefWidth();
			if (col.getMaxWidth() == Double.MAX_VALUE) {
				hasInfiniteMaxWidth = true;
				infiniteMaxWidthColumns++;
			}
		}
		List<Double> idealDoubleWidths = calculateProportionalWidths(visibleColumns, availableWidth, totalMinWidth, totalPrefWidth, hasInfiniteMaxWidth, infiniteMaxWidthColumns);

		// Round values and calculate remainder
		long roundedSumOthers = 0;
		int lastIndex = columnCount - 1;

		for (int i = 0; i < lastIndex; i++) {
			TableColumn<T, ?> col = visibleColumns.get(i);
			long roundedWidth = Math.round(idealDoubleWidths.get(i));
			// Ensure min/max constraints AFTER rounding
			roundedWidth = Math.max(roundedWidth, (long) Math.ceil(col.getMinWidth()));
			if (col.getMaxWidth() != Double.MAX_VALUE) {
				roundedWidth = Math.min(roundedWidth, (long) Math.floor(col.getMaxWidth()));
			}
			finalWidths.set(i, (double) roundedWidth);
			roundedSumOthers += roundedWidth;
		}

		long targetTotalWidth = (long) Math.floor(availableWidth); // Target the integer floor
		long remainingForLast = targetTotalWidth - roundedSumOthers;

		// Handle last column
		if (lastIndex >= 0) { // Ensure there is at least one column
			TableColumn<T, ?> lastCol = visibleColumns.get(lastIndex);
			long lastColMinWidth = (long) Math.ceil(lastCol.getMinWidth());
			long lastColMaxWidth = (lastCol.getMaxWidth() == Double.MAX_VALUE) ? Long.MAX_VALUE : (long) Math.floor(lastCol.getMaxWidth());

			// Calculate ideal width for last column based on remainder, then clamp
			long lastColFinalIntWidth = Math.max(lastColMinWidth, Math.min(remainingForLast, lastColMaxWidth));

            // Handle potential negative remaining space if previous columns were pushed up by minWidths
            if (remainingForLast < lastColMinWidth) {
                lastColFinalIntWidth = lastColMinWidth; // Must respect minWidth
                // Optional: Log this scenario if it's helpful
                 log.debug("Remaining space ({}) for last column is less than its minWidth ({}), clamping to minWidth.", remainingForLast, lastColMinWidth);
            }

            finalWidths.set(lastIndex, (double) lastColFinalIntWidth);

			// Final check logging - only perform sum if debug is enabled
			if (log.isDebugEnabled()) { // Replaced if(isLoggingEnabled)
				long finalSum = 0;
				for (Double w : finalWidths) {
					finalSum += Math.round(w); // Calculation only if debug enabled
				}
				// Use SLF4J placeholders
				log.debug("Integer Calculation: TargetWidth={}, SumOthers={}, Remainder={}, LastColFinal={}, FinalSum={}",
				          targetTotalWidth, roundedSumOthers, remainingForLast, lastColFinalIntWidth, finalSum);
				if (finalSum > targetTotalWidth && Math.abs(finalSum - targetTotalWidth) > 1) { // Allow small rounding diffs
					log.warn("Final integer sum ({}) exceeds target ({}), likely due to minWidth constraints.", finalSum, targetTotalWidth);
				} else if (finalSum < targetTotalWidth && columnCount > 0 && Math.abs(visibleColumns.get(lastIndex).getMaxWidth() - lastColFinalIntWidth) < 0.01 && lastCol.getMaxWidth() != Double.MAX_VALUE) {
                    // Check if last column was clamped by its own maxWidth
					log.warn("Final integer sum ({}) < target ({}), likely due to last column's maxWidth ({}) constraint.", finalSum, targetTotalWidth, lastColMaxWidth);
				}
                // Consider adding a check if the sum is less than the target without the last column being maxed out (could indicate an issue)
                else if (finalSum < targetTotalWidth && (lastCol.getMaxWidth() == Double.MAX_VALUE || Math.abs(lastColFinalIntWidth - lastColMaxWidth) > 1) ) {
                     log.debug("Final integer sum ({}) < target ({}) - possible rounding effects or remaining space couldn't be fully allocated.", finalSum, targetTotalWidth);
                }
			}
		} else {
             log.debug("No columns to process in integer width calculation.");
        }

		return finalWidths;
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
			appliedTotalWidth += col.getPrefWidth(); // Use actual prefWidth after potential set for sum
		}
		log.debug("Applied Calculated Total PrefWidth: {}", appliedTotalWidth); // fine -> debug, use {}
	}

	/**
	 * Forces an immediate recalculation and application of column widths. This stops any pending debounced resize operations from the table width listener. Useful if an external
	 * event (e.g., programmatically changing column visibility or after manually setting table items) requires an immediate layout update.
	 */
	public void forceResizeColumns() {
		log.debug("forceResizeColumns() called externally."); // fine -> debug
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
