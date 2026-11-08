// A DOM/event boundary for executing the actual static enhancement in embedded V8.
class EventTarget {
  constructor() { this.listeners = new Map(); }
  addEventListener(type, callback) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(callback);
  }
  removeEventListener(type, callback) { this.listeners.get(type)?.delete(callback); }
  dispatchEvent(event) { for (const callback of [...(this.listeners.get(event.type) || [])]) callback(event); }
}
class Event { constructor(type) { this.type = type; } }
class Element extends EventTarget {
  constructor(tagName, className = '') {
    super();
    this.tagName = tagName;
    this.className = className;
    this.children = [];
    this.parentNode = null;
    this.attributes = new Map();
    this.open = false;
  }

  append(...children) {
    for (const child of children) {
      child.remove();
      child.parentNode = this;
      this.children.push(child);
    }
  }

  before(child) {
    child.remove();
    child.parentNode = this.parentNode;
    this.parentNode.children.splice(this.parentNode.children.indexOf(this), 0, child);
  }

  remove() {
    if (this.parentNode) {
      this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1);
      this.parentNode = null;
    }
  }

  setAttribute(name, value) { this.attributes.set(name, value); }

  querySelectorAll(selector) {
    return this.children.flatMap(child => [
      ...(selector.startsWith('.')
        ? child.className.split(/\s+/).includes(selector.slice(1))
        : child.tagName === selector) ? [child] : [],
      ...child.querySelectorAll(selector),
    ]);
  }

  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
}

class Document extends Element {
  constructor(...children) {
    super('document');
    this.append(...children);
  }

  createElement(tagName) { return new Element(tagName); }
}

function inventory(...states) {
  const section = new Element('section', 'dependency-inventory');
  const heading = new Element('h2');
  const table = new Element('table', 'dependency-table');
  for (const open of states) {
    const body = new Element('tbody', 'dependency-entry');
    const row = new Element('tr', 'dependency-details-row');
    const cell = new Element('td');
    const details = new Element('details', 'dependency-details');
    details.open = open;
    details.append(new Element('summary', 'dependency-summary'), new Element('div', 'dependency-metadata'));
    cell.append(details);
    row.append(cell);
    body.append(row);
    table.append(body);
  }
  section.append(heading, table);
  return section;
}


var groups = [inventory(true, false), inventory(false, true)];
var document = new Document(...groups);
var window = new EventTarget();
var states = () => JSON.stringify(groups.map(group => group.querySelectorAll('.dependency-details').map(d => d.open)));
