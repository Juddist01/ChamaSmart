
function getCsrfToken() {
  const tokenElement = document.getElementById('csrfToken');
  return tokenElement ? tokenElement.value : '';
}

function getRequestHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  const token = getCsrfToken();

  if (token) {
    headers['Csrf-Token'] = token;
  }

  return headers;
}

function showError(form, message) {
  let el = form.querySelector('.form-error');

  if (!el) {
    el = document.createElement('p');
    el.className = 'form-error';
    const btn = form.querySelector('button[type="submit"]');

    if (btn) {
      form.insertBefore(el, btn);
    } else {
      form.appendChild(el);
    }
  }

  el.textContent = message;
  el.style.display = 'block';
}

function clearError(form) {
  const el = form.querySelector('.form-error');
  if (el) el.style.display = 'none';
}

function setLoading(btn, text) {
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = text;

  return () => {
    btn.disabled = false;
    btn.textContent = orig;
  };
}

/* --- Sign In ------------------------------------------------- */

const signInForm = document.getElementById('signInForm');

if (signInForm) {
  signInForm.addEventListener('submit', async function(e) {
    e.preventDefault();
    clearError(this);

    const btn = this.querySelector('button[type="submit"]');
    const restore = setLoading(btn, 'Signing in…');

    const payload = {
      email: this.querySelector('[name="email"]').value.trim(),
      password: this.querySelector('[name="password"]').value
    };

    try {
      const res = await fetch('/api/signin', {
        method: 'POST',
        headers: getRequestHeaders(),
        body: JSON.stringify(payload)
      });

      const data = await res.json();

      if (res.ok) {
        window.location.href = data.redirect;
      } else {
        showError(this, data.error || 'Sign in failed. Please try again.');
        restore();
      }
    } catch (err) {
      showError(this, 'Network error — please check your connection.');
      restore();
    }
  });
}

/* --- Sign Up ------------------------------------------------- */

const signUpForm = document.getElementById('registerForm');

if (signUpForm) {
  signUpForm.addEventListener('submit', async function(e) {
    e.preventDefault();
    clearError(this);

    const password = this.querySelector('[name="password"]').value;
    const confirmPassword =
      this.querySelector('[name="confirm_password"]').value;

    if (password !== confirmPassword) {
      showError(this, 'Passwords do not match.');
      return;
    }

    const btn = this.querySelector('button[type="submit"]');
    const restore = setLoading(btn, 'Creating account…');

    const payload = {
      fullName: this.querySelector('[name="fullName"]').value.trim(),
      email: this.querySelector('[name="email"]').value.trim(),
      phoneNo: this.querySelector('[name="phone"]').value.trim(),
      password: password,
      confirm_password: confirmPassword,
      chama_name: this.querySelector('[name="chama_name"]').value.trim(),
      role: this.querySelector('[name="role"]').value,
      accept_terms: this.querySelector('[name="accept_terms"]').checked
    };

    try {
      const res = await fetch('/api/signup', {
        method: 'POST',
        headers: getRequestHeaders(),
        body: JSON.stringify(payload)
      });

      const data = await res.json();

      if (res.ok) {
        window.location.href = '/sign-in?created=1';
      } else {
        showError(this, data.error || 'Registration failed. Please try again.');
        restore();
      }
    } catch (err) {
      showError(this, 'Network error — please check your connection.');
      restore();
    }
  });
}

/* --- Loan Approval ------------------------------------------- */

document.addEventListener('click', async function(e) {
  const button = e.target.closest('[data-approve-loan]');

  if (!button) return;

  const loanId = Number(button.dataset.approveLoan);

  if (!Number.isInteger(loanId) || loanId <= 0) {
    alert('Invalid loan ID.');
    return;
  }

  if (!confirm('Are you sure you want to approve this loan?')) {
    return;
  }

  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = 'Approving…';

  try {
    const res = await fetch('/api/loans/approve', {
      method: 'POST',
      headers: getRequestHeaders(),
      body: JSON.stringify({ loanId: loanId })
    });

    const data = await res.json();

    if (!res.ok) {
      throw new Error(data.error || data.message || 'Loan approval failed.');
    }

    alert(data.message || 'Loan approved successfully.');

    const row = button.closest('tr');

    if (row) {
      row.dataset.approvalStatus = 'approved';
      button.remove();

      const status = row.querySelector('[data-loan-status]');

      if (status) {
        status.textContent = 'Approved';
      }
    } else {
      button.textContent = 'Approved';
      button.disabled = true;
    }

  } catch (err) {
    alert(err.message || 'Unable to approve loan. Please try again.');
    button.disabled = false;
    button.textContent = originalText;
  }
});

/* --- Registration success banner ---------------------------- */

if (window.location.search.includes('created=1')) {
  const form = document.getElementById('signInForm');

  if (form) {
    const banner = document.createElement('div');
    banner.className = 'form-success';
    banner.textContent = 'Account created! Please sign in.';
    form.parentNode.insertBefore(banner, form);
  }
}
