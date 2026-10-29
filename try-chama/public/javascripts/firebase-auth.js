// ChamaSmart - "Continue with Google / GitHub" (Firebase Authentication)
//
// How it works:
//   1. The person clicks a button and signs in with Google or GitHub in a pop-up (Firebase handles this).
//   2. Firebase gives the browser an ID token (a signed proof of who they are).
//   3. We send that token to our own server (POST /api/auth/firebase). The SERVER verifies it,
//      finds or creates the user in MySQL, and starts the normal ChamaSmart session cookie.
//
// The values below identify the Firebase project. They are public by design - they are not secrets.
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth,
  signInWithPopup,
  signOut,
  GoogleAuthProvider,
  GithubAuthProvider,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";

const firebaseConfig = {
  apiKey: "AIzaSyDrSb64HvubN2o300fV69XHDXA8hQjgjro",
  authDomain: "chamasmart-33429.firebaseapp.com",
  projectId: "chamasmart-33429",
  appId: "1:304882389404:web:32b82f8759340ffd29f859",
};

const auth = getAuth(initializeApp(firebaseConfig));
const buttons = Array.from(document.querySelectorAll("[data-provider]"));
const messageBox = document.getElementById("socialMessage");
const signUpForm = document.getElementById("registerForm"); // only exists on the Sign Up page

function showMessage(text, isError) {
  if (!messageBox) return;
  messageBox.textContent = text;
  messageBox.classList.toggle("is-error", Boolean(isError));
}

function setBusy(busy) {
  buttons.forEach((b) => {
    b.disabled = busy;
  });
}

function makeProvider(name) {
  if (name === "google") {
    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: "select_account" });
    return provider;
  }
  const provider = new GithubAuthProvider();
  provider.addScope("user:email"); // GitHub only shares the email if we ask for it
  return provider;
}

// On the Sign Up page we need the role, chama name, phone and terms before we can create an account.
function readSignUpDetails() {
  if (!signUpForm) return {};
  const value = (id) => {
    const el = document.getElementById(id);
    return el ? el.value.trim() : "";
  };
  const terms = signUpForm.querySelector('input[name="accept_terms"]');
  return {
    role: value("role"),
    chamaName: value("chama_name"),
    phoneNo: value("phone"),
    acceptTerms: Boolean(terms && terms.checked),
  };
}

function friendlyFirebaseError(error) {
  switch (error && error.code) {
    case "auth/popup-closed-by-user":
    case "auth/cancelled-popup-request":
      return "Sign-in was cancelled. You can try again.";
    case "auth/popup-blocked":
      return "Your browser blocked the sign-in window. Allow pop-ups for this site and try again.";
    case "auth/account-exists-with-different-credential":
      return "This email is already registered with a different sign-in method. Use the method you used before.";
    case "auth/unauthorized-domain":
      return "This website address is not yet allowed in Firebase. Add it under Authentication > Settings > Authorized domains.";
    case "auth/operation-not-allowed":
      return "This sign-in method is not switched on in Firebase yet.";
    case "auth/network-request-failed":
      return "The network connection failed. Check your internet and try again.";
    default:
      return "Sign-in failed. Please try again.";
  }
}

function safeRedirect(path) {
  return typeof path === "string" && path.startsWith("/") && !path.startsWith("//") ? path : "/member";
}

async function continueWith(providerName) {
  showMessage("", false);

  let details = {};
  if (signUpForm) {
    details = readSignUpDetails();
    if (!details.role || !details.chamaName || !details.acceptTerms) {
      showMessage("Before continuing, choose your role, enter your chama name and accept the terms.", true);
      return;
    }
  }

  setBusy(true);
  try {
    showMessage("Opening sign-in window...", false);
    const result = await signInWithPopup(auth, makeProvider(providerName));
    const idToken = await result.user.getIdToken();

    showMessage("Signing you in...", false);
    const response = await fetch("/api/auth/firebase", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken, ...details }),
    });
    const data = await response.json().catch(() => ({}));

    // Our server now holds the session; we do not need Firebase's own browser session as well.
    await signOut(auth).catch(() => {});

    if (!response.ok) {
      showMessage(data.error || "Sign-in failed. Please try again.", true);
      return;
    }
    window.location.assign(safeRedirect(data.redirect));
  } catch (error) {
    console.error("Firebase sign-in error:", error && error.code, error);
    showMessage(friendlyFirebaseError(error), true);
  } finally {
    setBusy(false);
  }
}

buttons.forEach((button) => {
  button.addEventListener("click", () => continueWith(button.dataset.provider));
});
