/*
 * 页面标注脚本（对齐课程第四章 7.5「页面标注」）。
 *
 * 为什么需要它：把整页 HTML 交给模型，既费 token 又会被 CSS/JS/不可见元素淹没，
 * 模型还得自己从几百个 div 里猜哪个能点。标注的做法是「在页面里跑一段脚本，
 * 只挑出人真正能操作的元素，给每个分配一个短引用」，模型只看到这份清单。
 *
 * 每个被标注的元素会被写入 data-testid="eN"，后续点击/输入就靠这个引用定位，
 * 因此不依赖任何写死的选择器——这是它能适配任意网站的根本原因。
 *
 * 本文件是一段「箭头函数表达式」，由调用方拼成 (函数体)(最大元素数) 后执行，
 * 返回值是一个 JSON 字符串（避免 CDP 对复杂返回值的序列化歧义）。
 *
 * 已知边界：不进入 iframe。内容完全内嵌在 iframe 里的页面会标注出 0 个元素，
 * 此时应提示用户而非静默失败。
 */
(maxElements) => {
    const MAX_TEXT = 80;

    const clean = (s) => (s || '').replace(/\s+/g, ' ').trim().slice(0, MAX_TEXT);

    const INTERACTIVE_TAGS = ['a', 'button', 'input', 'select', 'textarea', 'summary'];
    const INTERACTIVE_ROLES = ['button', 'link', 'checkbox', 'radio', 'tab', 'menuitem',
        'option', 'switch', 'textbox', 'searchbox', 'combobox', 'slider', 'spinbutton'];

    const roleOf = (el) => {
        const explicit = el.getAttribute('role');
        if (explicit) return explicit.toLowerCase();
        const tag = el.tagName.toLowerCase();
        if (tag === 'a') return el.hasAttribute('href') ? 'link' : 'button';
        if (tag === 'button') return 'button';
        if (tag === 'select') return el.multiple ? 'listbox' : 'combobox';
        if (tag === 'textarea') return 'textbox';
        if (tag === 'input') {
            const t = (el.getAttribute('type') || 'text').toLowerCase();
            if (t === 'checkbox' || t === 'radio') return t;
            if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
            if (t === 'file') return 'file';
            if (t === 'range') return 'slider';
            return 'textbox';
        }
        if (el.isContentEditable) return 'textbox';
        return 'element';
    };

    const isInteractive = (el) => {
        const tag = el.tagName.toLowerCase();
        if (INTERACTIVE_TAGS.includes(tag)) return true;
        const role = (el.getAttribute('role') || '').toLowerCase();
        if (INTERACTIVE_ROLES.includes(role)) return true;
        if (el.isContentEditable) return true;
        if (el.hasAttribute('onclick')) return true;
        const ti = el.getAttribute('tabindex');
        return ti !== null && Number(ti) >= 0;
    };

    /* 可见性：display/visibility/opacity 三道 + 尺寸兜底。
       课程原话是「不可见的元素没有必要发送给模型」，这里是把它落成代码。 */
    const isVisible = (el) => {
        if (!el.isConnected) return false;
        const st = getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden' || st.visibility === 'collapse') {
            return false;
        }
        if (parseFloat(st.opacity || '1') === 0) return false;
        const r = el.getBoundingClientRect();
        return r.width >= 2 && r.height >= 2;
    };

    const labelOf = (el) => {
        const tag = el.tagName.toLowerCase();
        if (tag === 'input' || tag === 'textarea' || tag === 'select') {
            const type = (el.getAttribute('type') || 'text').toLowerCase();
            const isButtonish = ['submit', 'button', 'reset'].includes(type);
            // 输入框：有值就用值（按钮类），否则依次退到 placeholder / aria-label / name
            if (isButtonish && el.value) return clean(el.value);
            return clean(el.getAttribute('placeholder') || el.getAttribute('aria-label')
                || el.getAttribute('name') || el.value || '');
        }
        return clean(el.innerText || el.getAttribute('aria-label')
            || el.getAttribute('title') || el.getAttribute('alt') || '');
    };

    const candidates = [];
    /* 先清掉上一轮标注留下的引用再发新的：元素隐藏/移除后不会参与本轮标注，
       旧 ref 却还残留在 DOM 上——不清的话页面上会出现重复 ref，
       后续 querySelector 会命中这些「幽灵元素」，把点击派发到看不见的地方。
       只清本脚本自己的 eN 命名空间，不碰页面自带的 data-testid。 */
    document.querySelectorAll('[data-testid]').forEach((el) => {
        const v = el.getAttribute('data-testid') || '';
        if (/^e\d+$/.test(v)) el.removeAttribute('data-testid');
    });
    const walk = (el) => {
        if (!el || el.nodeType !== 1) return;
        const tag = el.tagName.toLowerCase();
        if (tag === 'script' || tag === 'style' || tag === 'noscript'
            || tag === 'template' || tag === 'meta' || tag === 'link'
            || tag === 'head' || tag === 'iframe') {
            return;
        }
        const type = (el.getAttribute('type') || '').toLowerCase();
        if (type !== 'hidden' && isInteractive(el) && isVisible(el)) {
            candidates.push(el);
        }
        for (const child of el.children) walk(child);
    };
    walk(document.body || document.documentElement);

    /* 嵌套去重：外层可点击元素若内部还有可点击元素，只保留内层。
       点内层会冒泡触发外层，留两个只会让模型在两个引用之间反复犹豫。 */
    const kept = candidates.filter((el) => {
        for (const other of candidates) {
            if (other !== el && el.contains(other)) return false;
        }
        return true;
    });

    const limited = kept.slice(0, maxElements);

    const elements = limited.map((el, i) => {
        const ref = 'e' + (i + 1);
        el.setAttribute('data-testid', ref);
        const r = el.getBoundingClientRect();
        return {
            ref: ref,
            role: roleOf(el),
            text: labelOf(el),
            // 取中心点：后续点击按这个坐标派发真实鼠标事件，比 el.click() 更接近真人操作
            x: Math.round(r.left + r.width / 2),
            y: Math.round(r.top + r.height / 2)
        };
    });

    return JSON.stringify({
        url: location.href,
        title: document.title || '',
        totalFound: kept.length,
        elements: elements
    });
}
