import { Component, inject, signal } from '@angular/core';
import { I18nService } from '../../core/i18n/i18n.service';

@Component({
  selector: 'app-module-sample-embedded',
  imports: [],
  templateUrl: './sample-embedded.component.html',
  styleUrl: './sample-embedded.component.css',
})
export class SampleEmbedded {
  protected readonly i18n = inject(I18nService);
  protected readonly count = signal(0);
  protected readonly todos = signal([
    { id: 1, key: 'sample.todo-use-contract', done: true },
    { id: 2, key: 'sample.todo-share-session', done: true },
    { id: 3, key: 'sample.todo-deep-link', done: false },
  ]);

  protected toggle(id: number): void {
    this.todos.update((ts) => ts.map((t) => (t.id === id ? { ...t, done: !t.done } : t)));
  }
}
