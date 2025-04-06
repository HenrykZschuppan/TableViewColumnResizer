# JavaFX TableView Column Resizer

A utility class for JavaFX `TableView` that automatically resizes visible columns proportionally to fill the available horizontal space. It respects column minimum and maximum widths and correctly accounts for the vertical scrollbar's width.

## The Problem

Standard JavaFX `TableView` column resize policies have limitations:

*   **`CONSTRAINED_RESIZE_POLICY`:** Fills the available width but prevents the horizontal scrollbar from appearing, even if the sum of minimum column widths exceeds the table width. This can lead to hidden content or columns being squeezed below their minimum size.
*   **`UNCONSTRAINED_RESIZE_POLICY` (Default):** Allows the horizontal scrollbar to appear correctly when content overflows but does *not* automatically resize columns to fill available space if they are narrower than the table width, leaving empty space on the right.

This utility aims to provide the best of both worlds: fill available space when possible, but allow scrolling when minimum widths demand it.

## How it Works

`TableViewColumnResizer` works by:

1.  **Attaching Listeners:** It listens for changes to the `TableView`'s width, the list of visible columns, and the state (visibility and width) of the vertical `ScrollBar`.
2.  **Calculating Available Width:** When triggered, it calculates the actual horizontal space available for columns, subtracting table padding/borders and the width of the vertical scrollbar *if it is currently visible*.
3.  **Distributing Width:** It distributes the calculated `availableWidth` proportionally among the visible columns based on their potential to grow (`maxWidth - prefWidth`) or shrink (`prefWidth - minWidth`), respecting their defined `minWidth` and `maxWidth`.
4.  **Applying Width:** It sets the `prefWidth` property of each column. The JavaFX layout system then uses this preferred width.
5.  **Efficiency:** It uses debouncing for table width changes to avoid excessive calculations during window resizing and only applies width changes if they exceed a small threshold.
6.  **Lifecycle Management:** It automatically attaches/detaches its listeners when the `TableView` is added to/removed from a scene to prevent memory leaks.

**Note on Initial Display:** Due to JavaFX layout timing, there might be a brief visual adjustment ("flicker") when the `TableView` is first displayed or when the vertical scrollbar appears/disappears. This happens because the resize calculation is triggered by listeners *after* the scrollbar's state change is fully processed by the layout system.

## Usage

1.  **Requirement:** This resizer requires the `TableView`'s column resize policy to be set to `TableView.UNCONSTRAINED_RESIZE_POLICY`. The `install` method handles this automatically.
2.  **Installation:** Simply call the static `install` method after your `TableView` instance is created:

    ```java
    import izon.framework.tableview.TableViewColumnResizer; // Adjust import path if needed
    import javafx.scene.control.TableView;

    // ... inside your controller or setup code ...

    TableView<MyDataType> myTableView = new TableView<>();
    // ... configure your TableView and columns ...

    // Install the resizer
    TableViewColumnResizer.install(myTableView);

    // You can optionally store the returned instance if you need to call forceResizeColumns()
    // TableViewColumnResizer<MyDataType> resizer = TableViewColumnResizer.install(myTableView);
    ```

## Public Methods

*   **`static install(TableView<T> tableView)`:** Installs the resizer onto the TableView and sets the required `UNCONSTRAINED_RESIZE_POLICY`. Returns the resizer instance.
*   **`forceResizeColumns()`:** Explicitly triggers a resize calculation. **This is generally not needed** as the resizing is handled automatically by internal listeners. It might be considered in edge cases after complex programmatic changes to the table where listeners might not immediately reflect the desired state.

## Configuration

*   **`HORIZONTAL_PADDING_BUFFER`:** Inside the `TableViewColumnResizer.java` source code, there's a constant `HORIZONTAL_PADDING_BUFFER` (defaulting to `4.0`). This small buffer helps prevent the horizontal scrollbar from appearing due to tiny calculation overflows or internal TableView spacing/borders not covered by `getInsets()`. If you still see an unnecessary horizontal scrollbar with minimal overflow, you might need to slightly adjust this value in the source code based on your specific application's styling.

## License

This code is released under the **MIT License**. See the [LICENSE](LICENSE) file for details.

Copyright (c) 2025 Henryk Daniel Zschuppan, Mecklenburg-Vorpommern, Germany
Developed in Mecklenburg-Vorpommern, Germany.

## Issues / Contributing

Please report any bugs or suggest features via the [GitHub Issues](https://github.com/HenrykZschuppan/TableViewColumnResizer/issues) page for this repository.
