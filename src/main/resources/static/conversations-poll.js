(() => {
    const observation = document.getElementById('conversation-updates');
    const status = document.getElementById('poll-status');
    if (!observation?.dataset.refreshUrl || !status) return;
    let url;
    try {
        url = new URL(observation.dataset.refreshUrl, window.location.href);
        if (url.origin !== window.location.origin || url.username || url.password || url.hash) return;
    } catch {
        return;
    }
    let delay = 5000;
    let stopped = false;
    let timer;
    let activeRequest;
    const preserveCompositionPosition = change => {
        const active = document.activeElement;
        const composing = active?.closest('form[action="/conversations/messages"]') ? active : null;
        const previousTop = composing?.getBoundingClientRect().top;
        change();
        if (composing?.isConnected && document.activeElement === composing) {
            const shift = composing.getBoundingClientRect().top - previousTop;
            if (shift !== 0) window.scrollBy({top: shift, behavior: 'instant'});
        }
    };
    const announce = text => {
        if (status.textContent !== text) preserveCompositionPosition(() => { status.textContent = text; });
    };
    const schedule = () => {
        clearTimeout(timer);
        if (!stopped && !document.hidden && !activeRequest) timer = setTimeout(poll, delay);
    };
    const replace = content => {
        const focused = observation.contains(document.activeElement) ? document.activeElement : null;
        const expanded = observation.querySelector('#captured-listing')?.open;
        preserveCompositionPosition(() => {
            observation.replaceChildren(...content.childNodes);
            const capture = observation.querySelector('#captured-listing');
            if (capture && expanded) capture.open = true;
        });
        if (focused) {
            const replacement = focused.id ? document.getElementById(focused.id) : null;
            (replacement && observation.contains(replacement) ? replacement : status).focus({preventScroll: true});
        }
    };
    const accessChanged = () => {
        stopped = true;
        const neutral = document.createElement('section');
        const heading = document.createElement('h1');
        heading.textContent = 'Conversación privada';
        neutral.append(heading);
        replace(neutral);
        announce('El acceso cambió; la consulta automática se detuvo y ocultó los mensajes y el contexto anteriores. Tus cambios sin guardar siguen en esta página. Consultá tu sesión y las obligaciones conservadas antes de continuar.');
    };
    const normalized = value => {
        const candidate = new URL(value, window.location.href);
        if (candidate.origin !== window.location.origin || candidate.username || candidate.password || candidate.hash) throw new Error('Consulta no disponible');
        candidate.searchParams.sort();
        return candidate.href;
    };
    const poll = () => refresh(url);
    const refresh = async (target, destination) => {
        if (stopped || document.hidden || activeRequest) return;
        const request = new AbortController();
        activeRequest = request;
        const timeout = setTimeout(() => request.abort('timeout'), 15000);
        try {
            const response = await fetch(target.href, {
                credentials: 'same-origin', cache: 'no-store', signal: request.signal,
                headers: {Accept: 'text/html', 'X-Requested-With': 'XMLHttpRequest'}
            });
            if (stopped || document.hidden || request.signal.aborted || activeRequest !== request) return;
            if (response.redirected || (response.status >= 400 && response.status < 500 && response.status !== 429)) {
                accessChanged();
                return;
            }
            if (!response.ok || !response.headers.get('Content-Type')?.includes('text/html')) throw new Error('Consulta no disponible');
            const html = await response.text();
            if (stopped || document.hidden || request.signal.aborted) return;
            const refreshed = new DOMParser().parseFromString(html, 'text/html').getElementById('conversation-updates');
            if (!refreshed || normalized(refreshed.dataset.refreshUrl) !== normalized(target.href)) throw new Error('Consulta no disponible');
            replace(refreshed);
            observation.dataset.refreshUrl = refreshed.dataset.refreshUrl;
            url = new URL(refreshed.dataset.refreshUrl, window.location.href);
            if (destination) {
                window.history.replaceState(null, '', destination.href);
                for (const link of document.querySelectorAll('a[data-history-context]')) {
                    const context = new URL(link.href);
                    if (context.origin !== destination.origin || context.pathname !== destination.pathname) continue;
                    const history = destination.searchParams.get('history');
                    if (history === null) context.searchParams.delete('history');
                    else context.searchParams.set('history', history);
                    link.href = context.href;
                }
            }
            delay = 5000;
            announce('Mensajes y contexto consultados con su acceso actual. Tus cambios sin guardar permanecen en esta página.');
        } catch {
            if (stopped || document.hidden || (request.signal.aborted && request.signal.reason !== 'timeout')) return;
            delay = Math.min(delay * 2, 60000);
            announce('Consulta temporalmente no disponible: los mensajes y el contexto pueden estar desactualizados. Se reintentará con espera. Tus cambios sin guardar permanecen en esta página.');
        } finally {
            clearTimeout(timeout);
            if (activeRequest === request) {
                activeRequest = null;
                schedule();
            }
        }
    };
    observation.addEventListener('click', event => {
        const link = event.target.closest('a[data-history-link]');
        if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey || stopped) return;
        let destination;
        let target;
        try {
            destination = new URL(link.href);
            normalized(destination.href);
            if (destination.pathname !== window.location.pathname) return;
            target = new URL(destination.href);
            target.pathname += '/updates';
            if (target.pathname !== url.pathname) return;
        } catch {
            return;
        }
        event.preventDefault();
        clearTimeout(timer);
        activeRequest?.abort('navigation');
        activeRequest = null;
        refresh(target, destination);
    });
    document.addEventListener('visibilitychange', () => {
        clearTimeout(timer);
        if (document.hidden) activeRequest?.abort('hidden');
        else schedule();
    });
    window.addEventListener('pagehide', () => {
        stopped = true;
        clearTimeout(timer);
        activeRequest?.abort('pagehide');
    });
    schedule();
})();
