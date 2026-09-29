import { request, post, showMessage } from './api.js';

const page = location.pathname;
const incomingReferral=new URLSearchParams(location.search).get('ref');
let referralCode=/^[a-f0-9]{32}$/i.test(incomingReferral||'')?incomingReferral.toUpperCase():null;
try{if(referralCode)sessionStorage.setItem('launchpad.invitation',referralCode);else referralCode=sessionStorage.getItem('launchpad.invitation');}catch{}
if(!/^[a-f0-9]{32}$/i.test(referralCode||''))referralCode=null;
const byId = id => document.getElementById(id);
let firebase, auth, provider, busy = false;
byId('year').textContent = new Date().getFullYear();
const modes = {
  '/register': ['A FRESH START', 'Make room for<br>your next big idea.', 'Create your account with Google. Simple, secure, and ready when you are.', 'Already have an account?', 'Login', '/login'],
  '/login': ['GOOD TO SEE YOU AGAIN', 'Welcome back.', 'Your next chapter is right where you left it.', 'New to Launchpad?', 'Register', '/register'],
  '/forgot-password': ['LET’S GET YOU BACK IN', 'Forgot your<br>password?', 'It happens. Enter your email and we’ll send you a link to reset your password.', 'Remember your password?', 'Back to login', '/login']
};
if (modes[page]) {
  const [kicker, title, description, prompt, label, href] = modes[page];
  document.title = `${label === 'Login' ? 'Register' : page === '/login' ? 'Login' : 'Reset password'} · Launchpad`;
  byId('section-kicker').textContent = kicker;
  byId('page-title').innerHTML = title; // Only static application-owned strings.
  byId('page-description').textContent = description;
  byId('landing-actions').hidden = true;
  byId('back-link').hidden = false;
  byId('auth-actions').hidden = page === '/forgot-password';
  byId('email-section').hidden = page !== '/login';
  byId('register-note').hidden = page !== '/register';
  byId('reset-form').hidden = page !== '/forgot-password';
  const switchPage = byId('switch-page');
  switchPage.hidden = false;
  switchPage.append(document.createTextNode(prompt + ' '));
  const link = document.createElement('a'); link.href = href; link.textContent = label; switchPage.append(link);
  initialize();
} else if (new URLSearchParams(location.search).has('loggedOut')) {
  showMessage('You’ve been signed out. See you next time.', 'success');
}

async function initialize() {
  try {
    const config = await request('/api/auth/config');
    if (!config.enabled) {
      showMessage('Sign-in is not available yet. Firebase setup is still in progress. Please check back soon.', 'info');
      return;
    }
    const [appSdk, authSdk] = await Promise.all([
      import('https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js'),
      import('https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js')
    ]);
    firebase = authSdk;
    auth = firebase.getAuth(appSdk.initializeApp(config.firebase));
    auth.useDeviceLanguage();
    // Only the HttpOnly server cookie persists. No reusable tokens in localStorage.
    await firebase.setPersistence(auth, firebase.inMemoryPersistence);
    provider = new firebase.GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    setBusy(false);
  } catch { showMessage('Unable to load secure sign-in. Check your connection and refresh the page.'); }
}

function setBusy(value, button) {
  busy = value;
  for (const id of ['google-button', 'login-button', 'reset-button']) {
    byId(id).disabled = value || !auth;
    byId(id).setAttribute('aria-busy', String(value && byId(id) === button));
    byId(id).querySelector('.spinner').hidden = !(value && byId(id) === button);
  }
  for (const input of document.querySelectorAll('input')) input.disabled = value;
}

const messages = {
  'auth/invalid-email': 'Enter a valid email address.',
  'auth/invalid-credential': 'The email or password is incorrect. If you registered with Google, use Continue with Google.',
  'auth/wrong-password': 'That password is incorrect. Try again or reset your password.',
  'auth/user-not-found': 'No account was found with that email. Register with Google to get started.',
  'auth/user-disabled': 'This account has been disabled. Please contact your administrator.',
  'auth/popup-closed-by-user': 'Google sign-in was cancelled. Select Continue with Google to try again.',
  'auth/cancelled-popup-request': 'Google sign-in was cancelled. Please try again.',
  'auth/popup-blocked': 'Your browser blocked the Google sign-in window. Allow popups for this site and try again.',
  'auth/account-exists-with-different-credential': 'This email uses another sign-in method. Sign in using your original method.',
  'auth/too-many-requests': 'Too many attempts. Please wait a few minutes before trying again.',
  'auth/network-request-failed': 'Connection interrupted. Check your internet connection and try again.',
  'auth/unauthorized-domain': 'Sign-in is not enabled for this website yet. Please contact your administrator.',
  'auth/operation-not-allowed': 'This sign-in method is not enabled yet. Please contact your administrator.',
  'auth/invalid-api-key': 'Sign-in configuration is incomplete. Please contact your administrator.',
  'auth/configuration-not-found': 'Firebase Authentication is not configured for this project. In Firebase Console, open Authentication, click Get started, and enable Google and Email/Password sign-in. Also confirm the web API key belongs to this project.',
  'auth/web-storage-unsupported': 'Your browser is blocking storage needed for Google sign-in. Allow site storage and try again.'
};

async function authenticate(button, signIn) {
  if (busy || !auth) return;
  showMessage(''); setBusy(true, button);
  try {
    // Invoke synchronously from the click handler so the actual Google popup is not blocked.
    const result = await signIn();
    await post('/api/auth/session', { idToken: await result.user.getIdToken(true) });
    let destination='/dashboard';
    if(referralCode){try{await post('/api/invitations/bind',{code:referralCode});try{sessionStorage.removeItem('launchpad.invitation');}catch{}}
      catch{destination='/features/invitation?ref='+encodeURIComponent(referralCode);}}
    await firebase.signOut(auth);
    location.replace(destination);
  } catch (error) {
    if (auth.currentUser) await firebase.signOut(auth).catch(() => {});
    const safeCode = typeof error.code === 'string' && /^auth\/[a-z0-9-]+$/.test(error.code) ? error.code : null;
    showMessage(messages[error.code] || (safeCode ? `Unable to sign in (${safeCode}). Please try again or contact your administrator.` : 'Unable to sign in. ' + (error.code ? 'Please try again.' : error.message || 'Please try again.')));
    setBusy(false);
  }
}

byId('google-button').addEventListener('click', () => authenticate(byId('google-button'), () => firebase.signInWithPopup(auth, provider)));
byId('login-form').addEventListener('submit', event => {
  event.preventDefault();
  const email = byId('email').value.trim();
  const password = byId('password').value;
  authenticate(byId('login-button'), () => firebase.signInWithEmailAndPassword(auth, email, password));
});
byId('toggle-password').addEventListener('click', () => {
  const show = byId('password').type === 'password';
  byId('password').type = show ? 'text' : 'password';
  byId('toggle-password').textContent = show ? 'Hide' : 'Show';
  byId('toggle-password').setAttribute('aria-label', show ? 'Hide password' : 'Show password');
  byId('toggle-password').setAttribute('aria-pressed', String(show));
});
byId('reset-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !auth) return;
  const email = byId('reset-email').value.trim();
  showMessage(''); setBusy(true, byId('reset-button'));
  const confirmation = 'If an account uses this email, a password reset link is on its way. Check your inbox and spam folder.';
  try {
    await firebase.sendPasswordResetEmail(auth, email);
    showMessage(confirmation, 'success');
  } catch (error) {
    // Keep the reset response neutral, including for projects without enumeration protection.
    showMessage(error.code === 'auth/user-not-found' ? confirmation : messages[error.code] || 'Unable to send the reset email. Please try again.', error.code === 'auth/user-not-found' ? 'success' : 'error');
  } finally { setBusy(false); }
});

// Revalidate on history restoration; the server redirects an existing session to the dashboard.
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
