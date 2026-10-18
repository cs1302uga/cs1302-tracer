// Native disclosures own their state; these controls only enhance each static inventory.
let dispose = () => {};

export function onRouteDidUpdate() {
  // Docusaurus does not use this hook's return value as an effect cleanup.
  dispose();
  const cleanups = [];
  const allDetails = [];
  const listen = (target, type, callback) => {
    target.addEventListener(type, callback);
    cleanups.push(() => target.removeEventListener(type, callback));
  };

  for (const inventory of document.querySelectorAll('.dependency-inventory')) {
    const details = [...inventory.querySelectorAll('.dependency-details')];
    if (!details.length) continue;
    allDetails.push(...details);
    const controls = document.createElement('div');
    controls.className = 'dependency-controls';
    controls.setAttribute('role', 'group');
    controls.setAttribute('aria-label', 'Dependency details controls');
    for (const [label, open] of [['Expand all', true], ['Collapse all', false]]) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = label;
      listen(button, 'click', () => details.forEach(detail => { detail.open = open; }));
      controls.append(button);
    }
    inventory.querySelector('.dependency-table').before(controls);
    cleanups.push(() => controls.remove());
  }

  let beforePrint = null;
  const restore = () => {
    if (beforePrint !== null) {
      allDetails.forEach((detail, index) => { detail.open = beforePrint[index]; });
      beforePrint = null;
    }
  };
  if (allDetails.length) {
    listen(window, 'beforeprint', () => {
      // Some browsers can send more than one beforeprint event for one dialog.
      if (beforePrint === null) beforePrint = allDetails.map(detail => detail.open);
      allDetails.forEach(detail => { detail.open = true; });
    });
    listen(window, 'afterprint', restore);
  }
  dispose = () => {
    restore();
    cleanups.forEach(cleanup => cleanup());
  };
}
