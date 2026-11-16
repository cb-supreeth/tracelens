/* Static help only. Deliberately independent of routing, recording and stores. */
(function () {
  "use strict";

  const trigger = document.getElementById("help-open");
  const dialog = document.getElementById("help-dialog");
  const closeButton = document.getElementById("help-close");
  if (!trigger || !dialog || !closeButton) return;

  trigger.addEventListener("click", () => {
    if (!dialog.open) dialog.showModal();
  });
  closeButton.addEventListener("click", () => {
    if (dialog.open) dialog.close();
  });
  // Native modal dialog provides focus containment and makes other views inert.
  // Handle cancellation explicitly so Escape always follows the Close path.
  dialog.addEventListener("cancel", event => {
    event.preventDefault();
    if (dialog.open) dialog.close();
  });
  dialog.addEventListener("close", () => {
    trigger.focus({preventScroll: true});
  });
}());
