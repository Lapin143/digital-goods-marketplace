'use strict';

// Проверка из браузера: страница получена через шлюз, витрина отвечает через тот же адрес (один вход, один сертификат).
(function () {
  function mark(id, state, text) {
    var item = document.getElementById(id);
    if (item) {
      item.setAttribute('data-state', state);
      item.textContent = text;
    }
  }

  mark('check-gateway', 'ok', 'Страница получена через шлюз по ' + location.protocol.replace(':', '').toUpperCase() + ' (' + location.host + ')');

  fetch('/api/v1/products?limit=3', { headers: { Accept: 'application/json' }, cache: 'no-store' })
    .then(function (response) {
      if (!response.ok) {
        throw new Error('код ответа ' + response.status);
      }
      return response.json();
    })
    .then(function (page) {
      var items = Array.isArray(page) ? page : page && Array.isArray(page.items) ? page.items : null;
      mark('check-storefront', 'ok', 'Витрина отвечает через шлюз' + (items ? ': товаров в ответе ' + items.length : ''));
    })
    .catch(function (error) {
      mark('check-storefront', 'fail', 'Витрина не ответила: ' + error.message);
    });
})();
