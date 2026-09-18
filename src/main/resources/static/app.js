const app = document.querySelector('#app');
const userNav = document.querySelector('#user-nav');
let timer, offset = 0, currentUser = null;

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const date = s => new Date(s).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
const time = s => {
  const d = new Date(s);
  return d.toLocaleTimeString('en-GB', { timeZone: 'Asia/Seoul', hour12: false }) + '.' + String(d.getUTCMilliseconds()).padStart(3, '0');
};

function toast(s) {
  const t = document.querySelector('#toast');
  t.textContent = s;
  t.style.display = 'block';
  setTimeout(() => t.style.display = 'none', 5000);
}

async function api(path, method = 'GET', body) {
  const r = await fetch('/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Formlimpic': 'formlimpic' },
    body: body ? JSON.stringify(body) : undefined
  });
  const data = await r.json();
  if (!r.ok) throw Error(data.message || '요청에 실패했습니다. 다시 시도해주세요.');
  if (data.serverTime) offset = Date.parse(data.serverTime) - Date.now();
  return data;
}

const now = () => Date.now() + offset;

function phase(f) {
  if (now() >= Date.parse(f.expiresAt)) return '결과 공개';
  if (now() >= Date.parse(f.startsAt)) return '접수 중';
  if (now() >= Date.parse(f.startsAt) - 600000) return '미리 작성 가능';
  return '오픈 예정';
}

async function checkAuth() {
  try {
    const res = await api('/auth/me');
    currentUser = res.loggedIn ? res.user : null;
  } catch {
    currentUser = null;
  }
  renderNav();
}

function renderNav() {
  if (!userNav) return;
  if (currentUser) {
    userNav.innerHTML = `
      <span class="user-name">${esc(currentUser.username)}님</span>
      <a href="#my" class="button secondary btn-sm">마이페이지</a>
      <button type="button" class="secondary btn-sm" id="logout-btn">로그아웃</button>
    `;
    document.querySelector('#logout-btn').onclick = async () => {
      try {
        await api('/auth/logout', 'POST');
        currentUser = null;
        renderNav();
        toast('로그아웃되었습니다.');
        location.hash = '';
      } catch (e) {
        toast(e.message);
      }
    };
  } else {
    userNav.innerHTML = `
      <a href="#login" class="button secondary btn-sm">로그인</a>
      <a href="#signup" class="button btn-sm">회원가입</a>
    `;
  }
}

async function route() {
  clearInterval(timer);
  try {
    const raw = location.hash.slice(1);
    const [kind, id, extra] = raw.split('/');

    if (kind === 'login') return renderLogin();
    if (kind === 'signup') return renderSignup();
    if (kind === 'my') return await renderMyPage(id || 'submissions');
    if (kind === 'new') {
      if (!currentUser) {
        toast('폼림픽을 개설하려면 먼저 로그인해주세요.');
        location.hash = 'login';
        return;
      }
      return renderCreate();
    }
    if (kind === 'form') return await renderDetail(id);
    if (kind === 'submit') {
      if (!currentUser) {
        toast('신청을 위해 먼저 로그인해주세요.');
        location.hash = 'login';
        return;
      }
      return await renderWizard(id);
    }
    await renderHome();
  } catch (e) {
    toast(e.message);
    app.innerHTML = '<div class="empty">화면을 불러오지 못했습니다. <a href="#">홈으로</a></div>';
  }
}

function renderLogin() {
  app.innerHTML = `
    <div class="panel auth-box">
      <a class="back" href="#">← 홈으로</a>
      <h1>로그인</h1>
      <p>아이디와 비밀번호로 로그인하여 폼림픽에 참여하세요.</p>
      <form id="login-form">
        <label class="field">
          <span>아이디</span>
          <input name="username" required autocomplete="username" autofocus placeholder="아이디 입력">
        </label>
        <label class="field">
          <span>비밀번호</span>
          <input name="password" type="password" required autocomplete="current-password" placeholder="비밀번호 입력">
        </label>
        <button style="width:100%;margin-top:12px;">로그인</button>
      </form>
      <p class="small" style="text-align:center;margin-top:20px;">
        계정이 없으신가요? <a href="#signup">회원가입하기</a>
      </p>
    </div>
  `;
  document.querySelector('#login-form').onsubmit = async e => {
    e.preventDefault();
    const b = e.submitter;
    b.disabled = true;
    const f = new FormData(e.target);
    try {
      await api('/auth/login', 'POST', {
        username: f.get('username'),
        password: f.get('password')
      });
      await checkAuth();
      toast('로그인되었습니다.');
      location.hash = '';
    } catch (x) {
      toast(x.message);
      b.disabled = false;
    }
  };
}

function renderSignup() {
  app.innerHTML = `
    <div class="panel auth-box">
      <a class="back" href="#">← 홈으로</a>
      <h1>회원가입</h1>
      <p>가상의 아이디와 비밀번호로 안전하게 가입하세요.<br>비밀번호는 암호화(BCrypt)되어 저장됩니다.</p>
      <form id="signup-form">
        <label class="field">
          <span>아이디</span>
          <input name="username" maxlength="20" required autocomplete="username" autofocus placeholder="영문, 숫자, _ (3~20자)">
        </label>
        <label class="field">
          <span>비밀번호</span>
          <input name="password" type="password" minlength="6" maxlength="50" required autocomplete="new-password" placeholder="6자 이상 입력">
        </label>
        <button style="width:100%;margin-top:12px;">가입 및 로그인</button>
      </form>
      <p class="small" style="text-align:center;margin-top:20px;">
        이미 계정이 있으신가요? <a href="#login">로그인하기</a>
      </p>
    </div>
  `;
  document.querySelector('#signup-form').onsubmit = async e => {
    e.preventDefault();
    const b = e.submitter;
    b.disabled = true;
    const f = new FormData(e.target);
    try {
      await api('/auth/signup', 'POST', {
        username: f.get('username'),
        password: f.get('password')
      });
      await checkAuth();
      toast('회원가입이 완료되었습니다!');
      location.hash = '';
    } catch (x) {
      toast(x.message);
      b.disabled = false;
    }
  };
}

async function renderMyPage(tab = 'submissions') {
  if (!currentUser) {
    location.hash = 'login';
    return;
  }

  app.innerHTML = `
    <div class="panel">
      <a class="back" href="#">← 홈으로</a>
      <h1>마이페이지</h1>
      <p><strong class="user-name">${esc(currentUser.username)}</strong>님의 회원 정보 및 폼림픽 내역입니다.</p>

      <div class="membership-badge-card">
        <div class="eyebrow">MY MEMBERSHIP CODE</div>
        <div class="membership-code-text">${esc(currentUser.membershipCode)}</div>
        <p class="small" style="margin:4px 0 0;color:#8a4070;">
          회원가입 시 발급된 회원님만의 고유 멤버십 코드입니다.<br>
          어떤 폼림픽에 참여하든 별도 발급 없이 이 코드로 자동 접수됩니다.
        </p>
      </div>

      <div class="tab-bar">
        <button type="button" class="tab-btn ${tab === 'submissions' ? 'active' : ''}" id="tab-submissions">내가 참여한 폼림픽</button>
        <button type="button" class="tab-btn ${tab === 'forms' ? 'active' : ''}" id="tab-forms">내가 만든 폼림픽</button>
      </div>

      <div id="tab-content">불러오는 중...</div>
    </div>
  `;

  document.querySelector('#tab-submissions').onclick = () => location.hash = 'my/submissions';
  document.querySelector('#tab-forms').onclick = () => location.hash = 'my/forms';

  const container = document.querySelector('#tab-content');

  if (tab === 'submissions') {
    const data = await api('/my/submissions');
    if (!data.submissions || data.submissions.length === 0) {
      container.innerHTML = '<div class="empty">참여한 폼림픽이 없습니다. 선착순 접수에 도전해보세요!</div>';
      return;
    }
    container.innerHTML = data.submissions.map(s => {
      const f = s.form;
      const r = s.receipt;
      const isExpired = now() >= Date.parse(f.expiresAt);
      return `
        <div class="sub-card">
          <div class="sub-card-header">
            <a class="sub-card-title" href="#form/${f.id}">${esc(f.title)}</a>
            <span class="badge">${phase(f)}</span>
          </div>
          <p class="muted">
            접수 시각: ${date(r.receivedAt)}.${String(new Date(r.receivedAt).getMilliseconds()).padStart(3, '0')} (${r.early ? '조기 제출' : '정상'})
          </p>
          <dl class="summary" style="margin: 12px 0;">
            <dt>멤버십 코드</dt><dd><strong>${s.ticket.code}</strong></dd>
            <dt>신청 이름</dt><dd>${esc(r.name)}</dd>
            <dt>최종 결과</dt>
            <dd>
              ${isExpired
                ? (s.rank > 0 ? `<span class="sub-rank">${s.rank}위</span>` : '집계 완료')
                : '<span class="muted">마감 후 결과 공개</span>'}
            </dd>
          </dl>
          <a class="small" href="#form/${f.id}">폼림픽 화면 보기 ↗</a>
        </div>
      `;
    }).join('');
  } else {
    const data = await api('/my/forms');
    if (!data.forms || data.forms.length === 0) {
      container.innerHTML = '<div class="empty">개설한 폼림픽이 없습니다. 새로운 폼림픽을 열어보세요! <br><br><a class="button" href="#new">＋ 폼림픽 만들기</a></div>';
      return;
    }
    container.innerHTML = `
      <div style="text-align:right;margin-bottom:16px;">
        <a class="button" href="#new">＋ 새 폼림픽 만들기</a>
      </div>
      <div class="grid">
        ${data.forms.map(f => `
          <a class="card" href="#form/${f.id}">
            <span class="badge">${phase(f)}</span>
            <h2>${esc(f.title)}</h2>
            <p class="muted">신청 ${date(f.startsAt)}<br>마감 ${date(f.expiresAt)}</p>
            <span class="small">결과 및 답변 확인 ↗</span>
          </a>
        `).join('')}
      </div>
    `;
  }
}

async function renderHome() {
  const data = await api('/forms');
  app.innerHTML = `
    <section class="hero">
      <div>
        <div class="eyebrow">THE PERFECT TIMING</div>
        <h1>정각의 순간,<br>폼림픽으로 연습하세요.</h1>
        <p>미리 작성하고, 원하는 순간에 제출하세요.<br>접수 시간부터 최종 순위까지 한눈에 확인할 수 있어요.</p>
      </div>
      <div class="hero-art">⏱</div>
    </section>
    <div class="notice">연습용 서비스입니다. 이름과 연락처에는 반드시 가상 정보를 입력하세요.</div>
    <div class="section-head">
      <h2>열려 있는 폼림픽 <span class="muted">${data.forms.length}</span></h2>
      <a class="button" href="#new">＋ 폼림픽 만들기</a>
    </div>
    <div class="grid">
      ${data.forms.slice().reverse().map(f => `
        <a class="card" href="#form/${f.id}">
          <span class="badge">${phase(f)}</span>
          <h2>${esc(f.title)}</h2>
          <p class="muted">신청 ${date(f.startsAt)}<br>마감 ${date(f.expiresAt)}</p>
          <span class="small">폼림픽 확인하기 ↗</span>
        </a>
      `).join('')}
    </div>
    ${data.forms.length ? '' : '<div class="empty">아직 폼림픽이 없어요. 첫 연습을 열어보세요.</div>'}
  `;
}

function renderCreate() {
  app.innerHTML = `
    <div class="panel">
      <a class="back" href="#">← 목록으로</a>
      <h1>폼림픽 만들기</h1>
      <p>신청 시작 10분 전부터 폼을 작성할 수 있어요.</p>
      <form id="create">
        <label class="field">
          <span>제목</span>
          <input name="title" maxlength="120" required placeholder="예: 5시 정각 폼림픽 연습">
        </label>
        <label class="field">
          <span>내용</span>
          <textarea name="content" maxlength="10000" required placeholder="폼림픽 안내를 적어주세요."></textarea>
        </label>
        <div class="dates">
          <label class="field">
            <span>신청 시작 · 기기 현지 시간</span>
            <input name="start" type="datetime-local" step="1" required>
          </label>
          <label class="field">
            <span>만료 · 기기 현지 시간</span>
            <input name="end" type="datetime-local" step="1" required>
          </label>
        </div>
        <div class="notice">정각 이전 제출은 정상 신청자 뒤에 배정됩니다. 만료 후 이름·멤버십 코드·순위·접수 시각이 공개됩니다.</div>
        <button>폼림픽 생성하기</button>
      </form>
    </div>
  `;
  document.querySelector('#create').onsubmit = async e => {
    e.preventDefault();
    const b = e.submitter;
    b.disabled = true;
    const f = new FormData(e.target);
    try {
      const r = await api('/forms', 'POST', {
        title: f.get('title'),
        content: f.get('content'),
        startsAt: new Date(f.get('start')).toISOString(),
        expiresAt: new Date(f.get('end')).toISOString()
      });
      location.hash = 'form/' + r.id;
    } catch (x) {
      toast(x.message);
      b.disabled = false;
    }
  };
}

async function renderDetail(id) {
  const d = await api('/forms/' + id);
  const f = d.form;
  app.innerHTML = `
    <div class="panel">
      <a class="back" href="#">← 전체 폼림픽</a>
      <p><span class="badge" id="phase">${phase(f)}</span></p>
      <h1>${esc(f.title)}</h1>
      <div class="content">${esc(f.content)}</div>
      <hr>
      <p>신청 시작 ${date(f.startsAt)}<br>만료 ${date(f.expiresAt)}<br><span class="small">한국 시간(KST) 기준</span></p>
      <div class="muted">서버 시각 추정 · 실제 판정은 서버 접수 시각 기준</div>
      <div class="clock" id="clock"></div>
      <div class="notice">실제 개인정보를 입력하지 마세요. 조기 제출은 정상 신청자 뒤로 배정됩니다.</div>
      <button id="enter"></button>
      <section id="mine"></section>
      <section id="results"></section>
    </div>
  `;
  const b = document.querySelector('#enter');
  b.onclick = () => {
    if (!currentUser && !d.mine) {
      toast('신청을 위해 먼저 로그인해주세요.');
      location.hash = 'login';
      return;
    }
    if (d.mine) showMine(d.mine);
    else location.hash = 'submit/' + id;
  };
  let loaded = false;
  const tick = async () => {
    if (!document.querySelector('#clock')) return;
    document.querySelector('#clock').textContent = time(now());
    document.querySelector('#phase').textContent = phase(f);
    b.textContent = d.mine ? '나의 답변 확인하기' : (now() >= Date.parse(f.expiresAt) ? '신청 마감' : (!currentUser ? '로그인 후 폼 제출' : '폼 제출'));
    b.disabled = !d.mine && (now() < Date.parse(f.startsAt) - 600000 || now() >= Date.parse(f.expiresAt));

    if (!loaded && now() >= Date.parse(f.expiresAt)) {
      loaded = true;
      try {
        const rows = await api('/forms/' + id + '/results');
        document.querySelector('#results').innerHTML = `
          <h2 class="top-space">최종 순위</h2>
          <p class="small">같은 밀리초의 신청은 서버 내부 접수 순번으로 구분합니다.</p>
          <div class="table-wrap">
            <table>
              <thead>
                <tr><th>순위</th><th>이름</th><th>멤버십 코드</th><th>접수 시각(KST)</th><th>구분</th></tr>
              </thead>
              <tbody>
                ${rows.map(r => `
                  <tr>
                    <td>${r.rank}</td>
                    <td>${esc(r.name)}</td>
                    <td>${r.membershipCode}</td>
                    <td title="${esc(date(r.receivedAt))}">${time(r.receivedAt)}</td>
                    <td>${r.early ? '조기 제출' : '정상'}</td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          </div>
          ${rows.length ? '' : '<p>제출된 답변이 없습니다.</p>'}
        `;
      } catch (e) {
        loaded = false;
      }
    }
  };
  tick();
  timer = setInterval(tick, 100);
}

function showMine(r) {
  document.querySelector('#mine').innerHTML = `
    <h2 class="top-space">나의 답변</h2>
    <dl class="summary">
      <dt>멤버십 코드</dt><dd>${r.code}</dd>
      <dt>이름</dt><dd>${esc(r.name)}</dd>
      <dt>연락처</dt><dd>${esc(r.phone)}</dd>
      <dt>접수 시각</dt><dd>${date(r.receivedAt)}.${String(new Date(r.receivedAt).getMilliseconds()).padStart(3, '0')}</dd>
      <dt>접수 구분</dt><dd>${r.early ? '조기 제출 · 정상 신청자 뒤 배정' : '정상 접수'}</dd>
    </dl>
    <p class="small">로그인 계정에 신청 내역이 안전하게 보관되어 있습니다.</p>
  `;
}

async function renderWizard(id) {
  const d = await api('/forms/' + id);
  if (d.mine) {
    location.hash = 'form/' + id;
    return;
  }
  const t = await api('/forms/' + id + '/ticket', 'POST');
  let step = 0;
  const key = 'draft:' + id + ':' + (currentUser ? currentUser.id : '');
  let draft = { name: '', phone: '' };
  try {
    draft = JSON.parse(sessionStorage.getItem(key)) || draft;
  } catch {}

  const render = () => {
    app.innerHTML = `
      <div class="wizard">
        <div class="actions">
          <a class="back" href="#form/${id}">← ${esc(d.form.title)}</a>
          <span class="muted">${step + 1} / 4</span>
        </div>
        <div class="progress"><div style="width:${(step + 1) * 25}%"></div></div>
        <form id="answer">
          <div class="step">
            ${step === 0 ? `
              <p class="muted">나의 고유 멤버십 코드 · 자동 적용</p>
              <h2>팬클럽 멤버십 번호 (6자리)</h2>
              <input readonly value="${t.code}" aria-label="멤버십 코드">
              <p class="small">회원님의 고유 멤버십 코드가 중복 없이 자동으로 적용되었습니다.</p>
            ` : step === 1 ? `
              <h2>연습용 이름 *</h2>
              <div class="notice">절대 실제 개인정보를 적지 마세요. 가상의 이름을 입력하세요.</div>
              <input name="name" maxlength="40" required value="${esc(draft.name)}" placeholder="예: 연습하는토끼" autocomplete="off">
            ` : step === 2 ? `
              <h2>연습용 연락처 *</h2>
              <div class="notice">절대 실제 개인정보를 적지 마세요. 실제 전화번호 대신 가상 값을 입력하세요.</div>
              <input name="phone" maxlength="30" required value="${esc(draft.phone)}" placeholder="예: TEST-0001" autocomplete="off">
            ` : `
              <h2>제출 준비가 되었어요.</h2>
              <dl class="summary">
                <dt>멤버십 코드</dt><dd>${t.code}</dd>
                <dt>연습용 이름</dt><dd>${esc(draft.name)}</dd>
                <dt>연습용 연락처</dt><dd>${esc(draft.phone)}</dd>
              </dl>
              <p>신청 시작 ${date(d.form.startsAt)}</p>
              <div class="clock" id="liveclock"></div>
              <div class="notice">시계는 참고용입니다. 시작 전 제출하면 정상 신청자 뒤에 배정되며, 제출 후 수정할 수 없습니다.</div>
            `}
          </div>
          <div class="actions">
            <button type="button" class="secondary" id="prev">${step ? '이전' : '닫기'}</button>
            <button id="next">${step === 3 ? '제출' : '다음'}</button>
          </div>
        </form>
      </div>
    `;

    document.querySelector('#prev').onclick = () => {
      save();
      if (step) {
        step--;
        render();
      } else {
        location.hash = 'form/' + id;
      }
    };

    document.querySelector('#answer').onsubmit = async e => {
      e.preventDefault();
      save();
      if (step < 3) {
        step++;
        render();
        return;
      }
      e.submitter.disabled = true;
      try {
        await api('/forms/' + id + '/submissions', 'POST', draft);
        sessionStorage.removeItem(key);
        toast('접수가 완료되었습니다.');
        location.hash = 'form/' + id;
      } catch (x) {
        toast(x.message + ' 동일한 제출을 다시 시도해도 중복 접수되지 않습니다.');
        e.submitter.disabled = false;
      }
    };
  };

  function save() {
    for (const k of ['name', 'phone']) {
      const el = document.querySelector(`[name="${k}"]`);
      if (el) draft[k] = el.value;
    }
    sessionStorage.setItem(key, JSON.stringify(draft));
  }

  render();
  timer = setInterval(() => {
    const el = document.querySelector('#liveclock');
    if (el) el.textContent = time(now());
  }, 50);
}

window.addEventListener('hashchange', route);
(async () => {
  await checkAuth();
  route();
})();
