const app = document.querySelector('#app');
const userNav = document.querySelector('#user-nav');
let timer, offset = 0, currentUser = null, adminOnlyFormCreation = false;

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
  if (data.adminOnlyFormCreation !== undefined) adminOnlyFormCreation = !!data.adminOnlyFormCreation;
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
  try {
    const formsRes = await api('/forms');
    adminOnlyFormCreation = !!formsRes.adminOnlyFormCreation;
  } catch {}
  renderNav();
}

function renderNav() {
  if (!userNav) return;
  if (currentUser) {
    const isAdmin = currentUser.role === 'ADMIN';
    userNav.innerHTML = `
      <span class="user-name">${esc(currentUser.username)}님${isAdmin ? ' <span class="admin-badge">👑 관리자</span>' : ''}</span>
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
      if (adminOnlyFormCreation && currentUser.role !== 'ADMIN') {
        toast('현재 관리자만 새 폼림픽을 개설할 수 있습니다.');
        location.hash = '';
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
  const isAdmin = currentUser.role === 'ADMIN';

  app.innerHTML = `
    <div class="panel">
      <a class="back" href="#">← 홈으로</a>
      <h1>마이페이지</h1>
      <p><strong class="user-name">${esc(currentUser.username)}</strong>님의 회원 정보 및 폼림픽 내역입니다.${isAdmin ? ' <span class="admin-badge">👑 관리자</span>' : ''}</p>

      ${isAdmin ? `
        <div class="admin-card">
          <div class="eyebrow">ADMINISTRATION CONSOLE</div>
          <h2 style="margin:6px 0 10px;font-size:18px;">👑 관리자 제어판</h2>
          <p class="small" style="margin:0 0 14px;">관리자 전용 권한 설정 및 폼림픽 생성 정책을 제어합니다.</p>
          <div class="admin-control-row">
            <div>
              <strong>폼림픽 개설 권한</strong>
              <div class="small muted" id="admin-status-desc" style="margin-top:4px;">
                ${adminOnlyFormCreation ? '🔒 현재 <strong>관리자만</strong> 새 폼림픽을 만들 수 있습니다.' : '🔓 현재 <strong>모든 회원</strong>이 자유롭게 폼림픽을 만들 수 있습니다.'}
              </div>
            </div>
            <button type="button" id="btn-toggle-creation" class="${adminOnlyFormCreation ? 'btn-toggle-on' : 'btn-toggle-off'}">
              ${adminOnlyFormCreation ? '🔒 관리자 전용 [ON]' : '🔓 전체 개설 허용 [OFF]'}
            </button>
          </div>
        </div>
      ` : ''}

      <div class="membership-badge-card">
        <div class="eyebrow">MY MEMBERSHIP CODE</div>
        <div class="membership-code-text">${esc(currentUser.membershipCode)}</div>
        <p class="small" style="margin:4px 0 0;color:#8a4070;">
          회원가입 시 발급된 회원님만의 고유 멤버십 코드입니다.<br>
          어떤 폼림픽에 참여하든 별도 발급 없이 이 코드로 자동 접수됩니다.
        </p>
      </div>

      <div class="webhook-card">
        <div class="eyebrow">DISCORD NOTIFICATION</div>
        <h2 style="margin:6px 0 10px;font-size:18px;">디스코드 결과 알림 웹훅</h2>
        <p class="small" style="margin:0 0 8px;">참여한 폼림픽이 마감되면 최종 순위와 접수 기록을 디스코드 채널로 실시간 발송해 드립니다.</p>
        <form id="webhook-form">
          <div class="webhook-row">
            <input name="webhook" placeholder="https://discord.com/api/webhooks/..." value="${esc(currentUser.discordWebhookUrl || '')}">
            <button type="submit" class="secondary">저장</button>
            <button type="button" id="btn-test-webhook" class="secondary" style="background:#f9f9f9;">테스트 전송</button>
          </div>
        </form>
      </div>

      <div class="tab-bar">
        <button type="button" class="tab-btn ${tab === 'submissions' ? 'active' : ''}" id="tab-submissions">내가 참여한 폼림픽</button>
        <button type="button" class="tab-btn ${tab === 'forms' ? 'active' : ''}" id="tab-forms">내가 만든 폼림픽</button>
      </div>

      <div id="tab-content">불러오는 중...</div>
    </div>
  `;

  if (isAdmin) {
    const toggleBtn = document.querySelector('#btn-toggle-creation');
    if (toggleBtn) {
      toggleBtn.onclick = async () => {
        toggleBtn.disabled = true;
        try {
          const nextState = !adminOnlyFormCreation;
          const res = await api('/admin/settings/form-creation', 'PUT', { adminOnly: nextState });
          adminOnlyFormCreation = !!res.adminOnlyFormCreation;
          toast(adminOnlyFormCreation ? '폼림픽 개설이 관리자 전용으로 설정되었습니다.' : '모든 회원이 폼림픽을 개설할 수 있도록 허용되었습니다.');
          await renderMyPage(tab);
        } catch (err) {
          toast(err.message);
          toggleBtn.disabled = false;
        }
      };
    }
  }

  document.querySelector('#webhook-form').onsubmit = async e => {
    e.preventDefault();
    const f = new FormData(e.target);
    const b = e.submitter;
    if (b) b.disabled = true;
    try {
      const res = await api('/my/webhook', 'PUT', { webhookUrl: f.get('webhook') });
      currentUser = res.user;
      toast('디스코드 웹훅 주소가 저장되었습니다.');
    } catch (err) {
      toast(err.message);
    } finally {
      if (b) b.disabled = false;
    }
  };

  document.querySelector('#btn-test-webhook').onclick = async e => {
    const btn = e.target;
    btn.disabled = true;
    const url = document.querySelector('[name="webhook"]').value.trim();
    try {
      const res = await api('/my/webhook/test', 'POST', { webhookUrl: url });
      toast(res.message);
    } catch (err) {
      toast(err.message);
    } finally {
      btn.disabled = false;
    }
  };

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
            <div style="display:flex;gap:6px;align-items:center;">
              <span class="badge">${phase(f)}</span>
              ${f.adminCreated ? '<span class="admin-badge">👑 관리자</span>' : ''}
            </div>
          </div>
          <p class="muted">
            접수 시각: ${date(r.receivedAt)}.${String(new Date(r.receivedAt).getMilliseconds()).padStart(3, '0')} (${r.early ? '조기 제출' : '정상'})
          </p>
          <dl class="summary" style="margin: 12px 0;">
            <dt>멤버십 코드</dt><dd><strong>${s.ticket.code}</strong></dd>
            <dt>신청 이름</dt><dd>${esc(r.name)}</dd>
            ${r.birthDate ? `<dt>생년월일</dt><dd>${esc(r.birthDate)}</dd>` : ''}
            ${r.bubble ? `<dt>버블</dt><dd>${esc(r.bubble)}</dd>` : ''}
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
    const canCreate = !adminOnlyFormCreation || (currentUser && currentUser.role === 'ADMIN');
    if (!data.forms || data.forms.length === 0) {
      container.innerHTML = `<div class="empty">개설한 폼림픽이 없습니다. 새로운 폼림픽을 열어보세요! <br><br>${canCreate ? '<a class="button" href="#new">＋ 폼림픽 만들기</a>' : '<span class="badge">🔒 현재 관리자만 개설 가능</span>'}</div>`;
      return;
    }
    container.innerHTML = `
      <div style="text-align:right;margin-bottom:16px;">
        ${canCreate ? '<a class="button" href="#new">＋ 새 폼림픽 만들기</a>' : '<span class="badge" style="background:#fee2e2;color:#991b1b;padding:8px 12px;font-weight:600;">🔒 관리자 전용 개설 모드</span>'}
      </div>
      <div class="grid">
        ${data.forms.map(f => `
          <a class="card" href="#form/${f.id}">
            <div class="badge-row">
              <span class="badge">${phase(f)}</span>
              ${f.adminCreated ? '<span class="admin-badge">👑 관리자</span>' : ''}
            </div>
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
  const canCreate = !adminOnlyFormCreation || (currentUser && currentUser.role === 'ADMIN');
  app.innerHTML = `
    <section class="hero">
      <div>
        <div class="eyebrow">THE PERFECT TIMING · RESCENE</div>
        <h1>리센느 사전 녹화 신청,<br>폼림픽으로 연습하세요.</h1>
        <p>미리 작성하고, 원하는 순간에 제출하세요.<br>접수 시간부터 최종 순위까지 한눈에 확인할 수 있어요.</p>
      </div>
      <img src="/hero.jpg" class="hero-art" alt="리센느">
    </section>
    <div class="notice">연습용 서비스입니다. 이름과 연락처에는 반드시 가상 정보를 입력하세요.</div>
    <div class="section-head">
      <h2>열려 있는 폼림픽 <span class="muted">${data.forms.length}</span></h2>
      ${canCreate ? '<a class="button" href="#new">＋ 폼림픽 만들기</a>' : '<span class="badge" style="background:#fee2e2;color:#991b1b;padding:8px 12px;font-weight:600;">🔒 관리자 전용 개설 모드</span>'}
    </div>
    <div class="grid">
      ${data.forms.slice().reverse().map(f => `
        <a class="card" href="#form/${f.id}">
          <div class="badge-row">
            <span class="badge">${phase(f)}</span>
            ${f.adminCreated ? '<span class="admin-badge">👑 관리자</span>' : ''}
          </div>
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
            <input name="start" type="datetime-local" required>
          </label>
          <label class="field">
            <span>신청 마감 · 기기 현지 시간</span>
            <input name="end" type="datetime-local" required>
          </label>
        </div>
        <label class="checkbox-field">
          <input name="hasBubble" type="checkbox">
          <span>버블(Bubble) 항목 추가</span>
        </label>
        <p class="small muted" style="margin-top:-2px;margin-bottom:18px;">체크 시 폼 신청 시 마지막에 버블 인증 입력란이 추가됩니다.</p>
        <div class="notice">신청 시각 이전 제출은 정상 신청자 뒤에 배정됩니다. 마감 후 이름·멤버십 코드·순위·접수 시각이 공개됩니다.</div>
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
        expiresAt: new Date(f.get('end')).toISOString(),
        hasBubble: f.get('hasBubble') === 'on'
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
  const isAdmin = currentUser && currentUser.role === 'ADMIN';

  app.innerHTML = `
    <div class="panel">
      <div class="form-header-actions">
        <a class="back" href="#">← 전체 폼림픽</a>
        ${isAdmin ? `<button type="button" id="btn-delete-form" class="btn-danger btn-sm">🗑️ 폼림픽 삭제</button>` : ''}
      </div>
      <div class="badge-row">
        <span class="badge" id="phase">${phase(f)}</span>
        ${f.adminCreated ? '<span class="admin-badge">👑 관리자</span>' : ''}
      </div>
      <h1>${esc(f.title)}</h1>
      <div class="content">${esc(f.content)}</div>
      <hr>
      <p>신청 시작 ${date(f.startsAt)}<br>신청 마감 ${date(f.expiresAt)}<br><span class="small">한국 시간(KST) 기준</span></p>
      <div class="notice">
        📌 <strong>폼림픽 참여 안내</strong><br>
        • 신청 시작 10분 전부터 폼을 미리 작성할 수 있습니다.<br>
        • <strong>신청 시각 이전 제출(조기 제출)은 정상 신청자 뒤에 배정</strong>되니 시작 시각에 맞춰 제출하세요.<br>
        • 신청 마감 시간에 최종 순위가 공개됩니다. 마이페이지에서 디스코드 웹훅을 등록할 시 알림이 발송됩니다.<br>
        • 연습용 서비스이므로 실제 개인정보 대신 가상 정보를 입력하세요.
      </div>
      <button id="enter"></button>
      <section id="mine"></section>
      <section id="results"></section>
    </div>
  `;

  if (isAdmin) {
    const delBtn = document.querySelector('#btn-delete-form');
    if (delBtn) {
      delBtn.onclick = async () => {
        if (!confirm(`'${f.title}' 폼림픽을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없으며 관련된 모든 접수 데이터가 영구히 삭제됩니다.`)) return;
        delBtn.disabled = true;
        try {
          await api('/forms/' + id, 'DELETE');
          toast('폼림픽이 성공적으로 삭제되었습니다.');
          location.hash = '';
        } catch (err) {
          toast(err.message);
          delBtn.disabled = false;
        }
      };
    }
  }

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
    if (!document.querySelector('#phase')) return;
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
  timer = setInterval(tick, 200);
}

function showMine(r) {
  document.querySelector('#mine').innerHTML = `
    <h2 class="top-space">나의 답변</h2>
    <dl class="summary">
      <dt>멤버십 코드</dt><dd>${r.code}</dd>
      <dt>이름</dt><dd>${esc(r.name)}</dd>
      ${r.birthDate ? `<dt>생년월일</dt><dd>${esc(r.birthDate)}</dd>` : ''}
      <dt>연락처</dt><dd>${esc(r.phone)}</dd>
      ${r.bubble ? `<dt>버블</dt><dd>${esc(r.bubble)}</dd>` : ''}
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
  const hasBubble = !!d.form.hasBubble;
  const totalSteps = hasBubble ? 5 : 4;
  let step = 0;
  const key = 'draft:' + id + ':' + (currentUser ? currentUser.id : '');
  let draft = { name: '', birthDate: '', phone: '', bubble: '' };
  try {
    draft = { ...draft, ...JSON.parse(sessionStorage.getItem(key)) };
  } catch {}

  const render = () => {
    const isLastStep = step === totalSteps - 1;
    app.innerHTML = `
      <div class="wizard">
        <div class="actions">
          <a class="back" href="#form/${id}">← ${esc(d.form.title)}</a>
          <span class="muted">${step + 1} / ${totalSteps}</span>
        </div>
        <div class="progress"><div style="width:${((step + 1) / totalSteps) * 100}%"></div></div>
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
              <input name="name" maxlength="40" required value="${esc(draft.name)}" placeholder="예: 연습하는토끼" autocomplete="off" autofocus>
            ` : step === 2 ? `
              <h2>연습용 생년월일 *</h2>
              <div class="notice">절대 실제 개인정보를 적지 마세요. 가상의 생년월일을 입력하세요.</div>
              <input name="birthDate" maxlength="20" required value="${esc(draft.birthDate)}" placeholder="예: 000101 (YYMMDD 6자리)" autocomplete="off" autofocus>
            ` : step === 3 ? `
              <h2>연습용 연락처 *</h2>
              <div class="notice">절대 실제 개인정보를 적지 마세요. 실제 전화번호 대신 가상 값을 입력하세요.</div>
              <input name="phone" maxlength="30" required value="${esc(draft.phone)}" placeholder="예: 01012345678" autocomplete="off" autofocus>
            ` : `
              <h2>연습용 버블(Bubble) *</h2>
              <div class="notice">절대 실제 개인정보를 적지 마세요. 가상의 버블 닉네임을 입력하세요.</div>
              <input name="bubble" maxlength="50" required value="${esc(draft.bubble)}" placeholder="예: 원이" autocomplete="off" autofocus>
            `}
          </div>
          <div class="actions">
            <button type="button" class="secondary" id="prev">${step ? '이전' : '닫기'}</button>
            <button id="next">${isLastStep ? '제출' : '다음'}</button>
          </div>
        </form>
      </div>
    `;

    // autofocus fallback
    const inp = document.querySelector('#answer input:not([readonly])');
    if (inp) inp.focus();

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
      if (step < totalSteps - 1) {
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
    for (const k of ['name', 'birthDate', 'phone', 'bubble']) {
      const el = document.querySelector(`[name="${k}"]`);
      if (el) draft[k] = el.value;
    }
    sessionStorage.setItem(key, JSON.stringify(draft));
  }

  render();
}

window.addEventListener('hashchange', route);
(async () => {
  await checkAuth();
  route();
})();
