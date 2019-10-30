import { PolymerElement } from '@polymer/polymer/polymer-element.js';
import { html } from '@polymer/polymer/lib/utils/html-tag.js';
import * as connectServices from '../generated/ConnectServices';

class TestComponent extends PolymerElement {
  static get template() {
    return html`
        <button id="button">Click</button>
        <button id="connect" on-click="connect">Click</button>
        <button id="connectAnonymous" on-click="connectAnonymous">Click anonymous</button>
        <div id="content"></div>
    `;
  }

  static get is() {
    return 'test-component'
  }

  connect(e) {
    connectServices
      .hello('Friend')
      .then(response => this.$.content.textContent = response)
      .catch(error => this.$.content.textContent = 'Error:' + error);
  }

  connectAnonymous(e) {
      connectServices
        .helloAnonymous()
        .then(response => this.$.content.textContent = response)
        .catch(error => this.$.content.textContent = 'Error:' + error);
    }
}
customElements.define(TestComponent.is, TestComponent);
