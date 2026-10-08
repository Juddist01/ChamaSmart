
function showError(form, message) {
  let el = form.querySelector('.form-error');
  if (!el) {
    el = document.createElement('p');
    el.className = 'form-error';
    const btn = form.querySelector('button[type="submit"]');
    form.insertBefore(el, btn);
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
  return () => { btn.disabled = false; btn.textContent = orig; };
}

/* --- Sign In ------------------------------------------------- */

const signInForm = document.getElementById('signInForm');
if (signInForm) {
  signInForm.addEventListener('submit', async function(e) {
    e.preventDefault();
    clearError(this);
    const restore = setLoading(this.querySelector('button[type="submit"]'), 'Signing in…');
    const payload = {
      email:    this.querySelector('[name="email"]').value.trim(),
      password: this.querySelector('[name="password"]').value
    };
    try {
      const res  = await fetch('/api/signin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
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
    const password        = this.querySelector('[name="password"]').value;
    const confirmPassword = this.querySelector('[name="confirm_password"]').value;
    if (password !== confirmPassword) {
      showError(this, 'Passwords do not match.');
      return;
    }
    const restore = setLoading(this.querySelector('button[type="submit"]'), 'Creating account…');
    const payload = {
      fullName:         this.querySelector('[name="fullName"]').value.trim(),
      email:            this.querySelector('[name="email"]').value.trim(),
      phoneNo:          this.querySelector('[name="phone"]').value.trim(),
      password:         password,
      confirm_password: confirmPassword,
      chamaName:        this.querySelector('[name="chama_name"]').value.trim(),
      role:             this.querySelector('[name="role"]').value,
      accept_terms:     this.querySelector('[name="accept_terms"]').checked
    };
    try {
      const res  = await fetch('/api/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
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

/* --- Show success banner on sign-in page after registration -- */

if (window.location.search.includes('created=1')) {
  const form = document.getElementById('signInForm');
  if (form) {
    const banner = document.createElement('div');
    banner.className = 'form-success';
    banner.textContent = 'Account created! Please sign in.';
    form.parentNode.insertBefore(banner, form);
  }
}