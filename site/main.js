const root = document.documentElement;
const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;

const bar = document.getElementById('appBar');
const onScroll = () => bar.classList.toggle('scrolled', scrollY > 8);
addEventListener('scroll', onScroll, { passive: true });
onScroll();

const reveals = [...document.querySelectorAll('.reveal')];
reveals.forEach(el => {
  const group = [...el.parentElement.children].filter(c => c.classList.contains('reveal'));
  el.style.setProperty('--rd', Math.min(group.indexOf(el) * 75, 375) + 'ms');
});
const io = new IntersectionObserver(entries => {
  entries.forEach(e => {
    if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
  });
}, { threshold: 0.12, rootMargin: '0px 0px -8% 0px' });
reveals.forEach(el => io.observe(el));

if (!reduced && matchMedia('(pointer: fine)').matches) {
  document.querySelectorAll('.tilt').forEach(el => {
    let raf = 0;
    el.addEventListener('pointermove', e => {
      const r = el.getBoundingClientRect();
      const x = (e.clientX - r.left) / r.width - 0.5;
      const y = (e.clientY - r.top) / r.height - 0.5;
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        el.classList.add('tilting');
        el.style.transform = `perspective(900px) rotateY(${(x * 7).toFixed(2)}deg) rotateX(${(y * -7).toFixed(2)}deg)`;
      });
    });
    el.addEventListener('pointerleave', () => {
      cancelAnimationFrame(raf);
      el.classList.remove('tilting');
      el.style.transform = '';
    });
  });
}

document.querySelectorAll('.diag-row i').forEach((el, i) => el.style.setProperty('--i', i));

const themeBtn = document.getElementById('themeBtn');
themeBtn.addEventListener('click', () => {
  const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
  const apply = () => {
    root.dataset.theme = next;
    localStorage.setItem('hd-theme', next);
  };
  document.startViewTransition ? document.startViewTransition(apply) : apply();
});
