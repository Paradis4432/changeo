(() => {
    const messages = document.getElementById('delivered-messages');
    if (!messages) return;
    const status = document.getElementById('poll-status');
    const url = messages.dataset.pollUrl;
    let after = Number(messages.dataset.after);
    let delay = 5000;
    let stopped = false;
    let timer;
    const schedule = () => { if (!stopped && !document.hidden) timer = setTimeout(poll, delay); };
    const poll = async () => {
        if (stopped || document.hidden) return;
        try {
            const response = await fetch(`${url}&after=${after}`, {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}});
            if (response.status === 401 || response.status === 403 || response.redirected) {
                stopped = true;
                status.textContent = 'El acceso cambió. Volvé a consultar tu sesión y las obligaciones conservadas.';
                return;
            }
            if (!response.ok) throw new Error('Consulta no disponible');
            const page = await response.json();
            if (page.next > after) {
                window.location.reload();
                return;
            }
            after = page.next;
            delay = page.more ? 1000 : 5000;
            status.textContent = 'Consulta actualizada; cada mensaje conserva su revisión y audiencia verificadas.';
        } catch (failure) {
            delay = Math.min(delay * 2, 60000);
            status.textContent = 'Consulta temporalmente no disponible. Se reintentará con espera; también podés actualizar la página.';
        }
        schedule();
    };
    document.addEventListener('visibilitychange', () => { clearTimeout(timer); schedule(); });
    window.addEventListener('pagehide', () => { stopped = true; clearTimeout(timer); });
    schedule();
})();
