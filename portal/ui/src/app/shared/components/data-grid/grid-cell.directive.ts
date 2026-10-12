import { Directive, inject, input, OnInit, TemplateRef } from '@angular/core';
import { DataGrid } from './data-grid.component';
import { GridCellContext } from './grid-column';

@Directive({ selector: '[gridCell]' })
export class GridCellDirective<T> implements OnInit {
  readonly gridCell = input.required<string>();
  private readonly template = inject(TemplateRef<GridCellContext<T>>);
  private readonly grid = inject(DataGrid<T>);

  ngOnInit(): void {
    this.grid.registerCell(this.gridCell(), this.template);
  }
}
