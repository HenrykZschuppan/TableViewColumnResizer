# JavaFX TableView Column Resizer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A JavaFX utility that automatically and **proportionally** resizes the columns (`TableColumn`) of a `TableView` to fill the available horizontal width.

**Core Features:**

*   Fills available horizontal space proportionally.
*   Respects the `minWidth` and `maxWidth` of each column.
*   Correctly accounts for the vertical scrollbar's width when visible.
*   Addresses the drawbacks of standard JavaFX resize policies (no empty space on the right, no content overflow below minimum width).
*   Simple installation via a static method.
*   Configurable buffer for fine-tuning.
*   Manages its own lifecycle (listeners are added/removed automatically).
*   Uses SLF4J for optional logging.

*(Note: Currently designed for `TableView` only, not `TreeTableView`)*.

## The Problem

Standard JavaFX `TableView` `columnResizePolicy` options have limitations:

*   **`CONSTRAINED_RESIZE_POLICY`:** Fills the width but prevents the horizontal scrollbar from appearing, even if column content is wider than the minimum width. Columns can get squeezed.
*   **`UNCONSTRAINED_RESIZE_POLICY` (Default):** Correctly shows the horizontal scrollbar but often leaves unused empty space on the right if columns are narrower than the table width.

This resizer combines the advantages: it fills the space when possible but allows scrolling when minimum widths require it.

## Requirements

*   JavaFX 11 or later (tested with JavaFX 21)
*   SLF4J API (optional, for logging)

## Installation & Usage

1.  **Add Dependency:** Ensure the `TableViewColumnResizer` class is available in your project (e.g., as part of a library or directly in your codebase).
2.  **Policy Requirement:** The resizer requires `TableView.UNCONSTRAINED_RESIZE_POLICY`. The `install` method sets this for you automatically.
3.  **Apply the Resizer:** Call the static `install` method *after* creating and configuring your `TableView`:

    ```java
    import izon.framework.tableview.TableViewColumnResizer; // Adjust the import path
    import javafx.scene.control.TableView;
    // ... other imports

    public class MyController {

        @FXML
        private TableView<MyDataType> myDataTable;

        public void initialize() {
            // ... configure your columns (setCellValueFactory etc.) ...

            // Install the resizer (default configuration)
            // It manages itself from now on.
            TableViewColumnResizer.install(myDataTable);

            // Optional: Keep the instance if forceResizeColumns() is needed later
            // TableViewColumnResizer<MyDataType> resizer = TableViewColumnResizer.install(myDataTable);
        }
    }
    ```

## Configuration (Horizontal Buffer)

By default, the resizer doesn't subtracts a padding (`0.0` pixels) from the available width. This buffer acts as a safety margin to compensate for things like CSS borders (`border-width`) or minor layout inaccuracies not covered by `table.getInsets()`.

**When to Adjust:**

*   You observe a **small, consistent gap** to the right of the last column.
*   A **horizontal scrollbar appears unnecessarily**, even though everything should fit.
*   
**How to Adjust:**

Use the overloaded `install` method to provide a custom buffer value:

Install the resizer WITHOUT an additional buffer (only insets are considered)  
`TableViewColumnResizer.install(myDataTable); // equals to install(myDataTable, 0.0)`

Install the resizer with a buffer of 2.0 pixel  
`TableViewColumnResizer.install(myDataTable, 2.0);  // for border width of 1px times two (both sides)`

**Recommendation:** Start with the default installation (`TableViewColumnResizer.install(myDataTable);`). Only adjust the buffer if you encounter the visual issues mentioned above.

## Advanced Usage

*   **`forceResizeColumns()`:**
    Calling this method on the `TableViewColumnResizer` instance triggers an immediate recalculation and application of column widths.
    ```java
    TableViewColumnResizer<MyDataType> resizer = TableViewColumnResizer.install(myDataTable);
    // ... later, after complex changes ...
    resizer.forceResizeColumns();
    ```
    **This is usually NOT necessary**, as internal listeners detect changes automatically. It might be useful in rare edge cases, e.g., after complex programmatic changes to columns or data where the layout needs an immediate update.


## Public Methods

*   **`static install(TableView<T> tableView)`:** Installs the resizer onto the TableView and sets the required `UNCONSTRAINED_RESIZE_POLICY`. Returns the resizer instance.
*   **`forceResizeColumns()`:** Explicitly triggers a resize calculation. **This is generally not needed** as the resizing is handled automatically by internal listeners. It might be considered in edge cases after complex programmatic changes to the table where listeners might not immediately reflect the desired state.

## How it Works Internally

`TableViewColumnResizer` works by:

1.  **Attaching Listeners:** It listens for changes to the `TableView`'s width, the list of visible columns, and the state (visibility and width) of the vertical `ScrollBar`.
2.  **Calculating Available Width:** When triggered, it calculates the actual horizontal space available for columns, subtracting table padding/borders and the width of the vertical scrollbar *if it is currently visible*.
3.  **Distributing Width:** It distributes the calculated `availableWidth` proportionally among the visible columns based on their potential to grow (`maxWidth - prefWidth`) or shrink (`prefWidth - minWidth`), respecting their defined `minWidth` and `maxWidth`.
4.  **Applying Width:** It sets the `prefWidth` property of each column. The JavaFX layout system then uses this preferred width.
5.  **Efficiency:** It uses debouncing for table width changes to avoid excessive calculations during window resizing and only applies width changes if they exceed a small threshold.
6.  **Lifecycle Management:** It automatically attaches/detaches its listeners when the `TableView` is added to/removed from a scene to prevent memory leaks.


**Note on Initial Scrollbar Detection:** The calculation accurately accounts for the vertical scrollbar. However, the visibility and final width of the scrollbar are determined by the JavaFX layout system based on the `TableView`'s content and available height. Since the `TableView` is often populated with data *after* the initial scene display, the scrollbar might not be visible or have its final dimensions immediately when the resizer performs its first checks.

This resizer addresses this by using internal listeners (`visibleProperty`, `widthProperty`) attached to the scrollbar. These listeners ensure that the column widths are correctly recalculated **as soon as** the layout system updates the scrollbar's state after the table is populated or resized. While this process relies on the standard layout and event timing, the listener-based approach ensures the layout eventually reflects the correct scrollbar state for accurate column sizing.

## License

This code is released under the **MIT License**. See the [LICENSE](LICENSE) file for details.

Copyright (c) 2025 Henryk Daniel Zschuppan

Location:
Developed in Mecklenburg-Vorpommern, Germany.

## Issues / Contributing

Please report any bugs or suggest features via the [GitHub Issues](https://github.com/HenrykZschuppan/TableViewColumnResizer/issues) page for this repository.
