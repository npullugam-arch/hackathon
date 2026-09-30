import {
    request,
    post,
    showMessage
} from './api.js';


/* =========================================================
   PAGE / REFERRAL
   ========================================================= */

const page = location.pathname;

const params =
    new URLSearchParams(location.search);

const incomingReferral =
    params.get('ref');


let referralCode =
    /^[a-f0-9]{32}$/i.test(
        incomingReferral || ''
    )
        ? incomingReferral.toUpperCase()
        : null;


try {

    if (referralCode) {

        sessionStorage.setItem(
            'launchpad.invitation',
            referralCode
        );

    } else {

        referralCode =
            sessionStorage.getItem(
                'launchpad.invitation'
            );

    }

} catch {}


if (
    !/^[a-f0-9]{32}$/i.test(
        referralCode || ''
    )
) {

    referralCode = null;

}


/* =========================================================
   HELPERS
   ========================================================= */

const byId = id =>
    document.getElementById(id);


const publicLanding =
    byId('public-landing');


const authShell =
    byId('auth-shell');


/* =========================================================
   YEAR
   ========================================================= */

const currentYear =
    new Date().getFullYear();


const yearElement =
    byId('year');


if (yearElement) {

    yearElement.textContent =
        currentYear;

}


document
    .querySelectorAll('.landing-year')
    .forEach(element => {

        element.textContent =
            currentYear;

    });


/* =========================================================
   PUBLIC LANDING PAGE
   ========================================================= */

if (
    page === '/' &&
    publicLanding
) {

    publicLanding.hidden = false;

    if (authShell) {
        authShell.hidden = true;
    }

    document.body.classList.add(
        'public-home'
    );


    /*
     * Smooth reveal animation.
     */

    const revealSections =
        publicLanding.querySelectorAll(
            '.reveal-section'
        );


    if (
        'IntersectionObserver'
        in window
    ) {

        const reveal =
            new IntersectionObserver(
                entries => {

                    entries.forEach(
                        entry => {

                            if (
                                entry.isIntersecting
                            ) {

                                entry.target
                                    .classList
                                    .add(
                                        'is-visible'
                                    );

                                reveal.unobserve(
                                    entry.target
                                );

                            }

                        }
                    );

                },
                {
                    threshold: 0.12
                }
            );


        revealSections.forEach(
            section =>
                reveal.observe(section)
        );

    } else {

        revealSections.forEach(
            section =>
                section.classList.add(
                    'is-visible'
                )
        );

    }

}


/* =========================================================
   AUTH PAGE
   ========================================================= */

let firebase;
let auth;
let provider;

let busy = false;


/* =========================================================
   AUTH MODES
   ========================================================= */

const modes = {

    '/register': [
        'A FRESH START',
        'Make room for<br>your next big idea.',
        'Create your account with Google. Simple, secure, and ready when you are.',
        'Already have an account?',
        'Login',
        '/login'
    ],

    '/login': [
        'GOOD TO SEE YOU AGAIN',
        'Welcome back.',
        'Your next chapter is right where you left it.',
        'New to Launchpad?',
        'Register',
        '/register'
    ],

    '/forgot-password': [
        'LET’S GET YOU BACK IN',
        'Forgot your<br>password?',
        'It happens. Enter your email and we’ll send you a link to reset your password.',
        'Remember your password?',
        'Back to login',
        '/login'
    ]

};


/* =========================================================
   AUTH PAGE SETUP
   ========================================================= */

if (modes[page]) {

    if (publicLanding) {
        publicLanding.hidden = true;
    }

    if (authShell) {
        authShell.hidden = false;
    }


    const [
        kicker,
        title,
        description,
        prompt,
        label,
        href
    ] = modes[page];


    document.title =
        page === '/register'
            ? 'Register · Launchpad'
            : page === '/login'
                ? 'Login · Launchpad'
                : 'Reset password · Launchpad';


    const sectionKicker =
        byId('section-kicker');

    if (sectionKicker) {
        sectionKicker.textContent =
            kicker;
    }


    const pageTitle =
        byId('page-title');

    if (pageTitle) {

        /*
         * These strings are static application-owned
         * values from the modes object.
         */

        pageTitle.innerHTML =
            title;
    }


    const pageDescription =
        byId('page-description');

    if (pageDescription) {

        pageDescription.textContent =
            description;

    }


    const landingActions =
        byId('landing-actions');

    if (landingActions) {
        landingActions.hidden = true;
    }


    const backLink =
        byId('back-link');

    if (backLink) {
        backLink.hidden = false;
    }


    const authActions =
        byId('auth-actions');

    if (authActions) {

        authActions.hidden =
            page === '/forgot-password';

    }


    const emailSection =
        byId('email-section');

    if (emailSection) {

        emailSection.hidden =
            page !== '/login';

    }


    const registerNote =
        byId('register-note');

    if (registerNote) {

        registerNote.hidden =
            page !== '/register';

    }


    const resetForm =
        byId('reset-form');

    if (resetForm) {

        resetForm.hidden =
            page !== '/forgot-password';

    }


    const switchPage =
        byId('switch-page');


    if (switchPage) {

        switchPage.hidden = false;

        switchPage.replaceChildren();

        switchPage.append(
            document.createTextNode(
                prompt + ' '
            )
        );


        const link =
            document.createElement('a');

        link.href = href;

        link.textContent = label;

        switchPage.append(link);

    }


    initialize();

}


/* =========================================================
   LOGGED OUT MESSAGE
   ========================================================= */

else if (
    new URLSearchParams(
        location.search
    ).has('loggedOut')
) {

    showMessage(
        'You’ve been signed out. See you next time.',
        'success'
    );

}


/* =========================================================
   FIREBASE INITIALIZATION
   ========================================================= */

async function initialize() {

    try {

        const config =
            await request(
                '/api/auth/config'
            );


        if (!config.enabled) {

            showMessage(
                'Sign-in is not available yet. Firebase setup is still in progress. Please check back soon.',
                'info'
            );

            return;
        }


        const [
            appSdk,
            authSdk
        ] = await Promise.all([

            import(
                'https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js'
            ),

            import(
                'https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js'
            )

        ]);


        firebase =
            authSdk;


        auth =
            firebase.getAuth(
                appSdk.initializeApp(
                    config.firebase
                )
            );


        auth.useDeviceLanguage();


        /*
         * Authentication state remains in memory.
         * The reusable server session is handled by
         * the HttpOnly server cookie.
         */

        await firebase.setPersistence(
            auth,
            firebase.inMemoryPersistence
        );


        provider =
            new firebase.GoogleAuthProvider();


        provider.setCustomParameters({
            prompt: 'select_account'
        });


        setBusy(false);

    } catch {

        showMessage(
            'Unable to load secure sign-in. Check your connection and refresh the page.'
        );

    }

}


/* =========================================================
   BUSY STATE
   ========================================================= */

function setBusy(
    value,
    button
) {

    busy = value;


    for (
        const id of [
            'google-button',
            'login-button',
            'reset-button'
        ]
    ) {

        const element =
            byId(id);


        if (!element) {
            continue;
        }


        element.disabled =
            value || !auth;


        element.setAttribute(
            'aria-busy',
            String(
                value &&
                element === button
            )
        );


        const spinner =
            element.querySelector(
                '.spinner'
            );


        if (spinner) {

            spinner.hidden =
                !(
                    value &&
                    element === button
                );

        }

    }


    document
        .querySelectorAll('input')
        .forEach(input => {

            input.disabled =
                value;

        });

}


/* =========================================================
   FIREBASE ERROR MESSAGES
   ========================================================= */

const messages = {

    'auth/invalid-email':
        'Enter a valid email address.',

    'auth/invalid-credential':
        'The email or password is incorrect. If you registered with Google, use Continue with Google.',

    'auth/wrong-password':
        'That password is incorrect. Try again or reset your password.',

    'auth/user-not-found':
        'No account was found with that email. Register with Google to get started.',

    'auth/user-disabled':
        'This account has been disabled. Please contact your administrator.',

    'auth/popup-closed-by-user':
        'Google sign-in was cancelled. Select Continue with Google to try again.',

    'auth/cancelled-popup-request':
        'Google sign-in was cancelled. Please try again.',

    'auth/popup-blocked':
        'Your browser blocked the Google sign-in window. Allow popups for this site and try again.',

    'auth/account-exists-with-different-credential':
        'This email uses another sign-in method. Sign in using your original method.',

    'auth/too-many-requests':
        'Too many attempts. Please wait a few minutes before trying again.',

    'auth/network-request-failed':
        'Connection interrupted. Check your internet connection and try again.',

    'auth/unauthorized-domain':
        'Sign-in is not enabled for this website yet. Please contact your administrator.',

    'auth/operation-not-allowed':
        'This sign-in method is not enabled yet. Please contact your administrator.',

    'auth/invalid-api-key':
        'Sign-in configuration is incomplete. Please contact your administrator.',

    'auth/configuration-not-found':
        'Firebase Authentication is not configured for this project. In Firebase Console, open Authentication, click Get started, and enable Google and Email/Password sign-in. Also confirm the web API key belongs to this project.',

    'auth/web-storage-unsupported':
        'Your browser is blocking storage needed for Google sign-in. Allow site storage and try again.'

};


/* =========================================================
   AUTHENTICATION
   ========================================================= */

async function authenticate(
    button,
    signIn
) {

    if (
        busy ||
        !auth
    ) {
        return;
    }


    showMessage(
        ''
    );


    setBusy(
        true,
        button
    );


    try {

        /*
         * Keep the sign-in invocation directly
         * inside the user-triggered flow.
         */

        const result =
            await signIn();


        const idToken =
            await result.user.getIdToken(
                true
            );


        await post(
            '/api/auth/session',
            {
                idToken
            }
        );


        let destination =
            '/dashboard';


        /*
         * Bind referral after successful
         * authentication.
         */

        if (referralCode) {

            try {

                await post(
                    '/api/invitations/bind',
                    {
                        code: referralCode
                    }
                );


                try {

                    sessionStorage.removeItem(
                        'launchpad.invitation'
                    );

                } catch {}

            } catch {

                destination =
                    '/features/invitation?ref=' +
                    encodeURIComponent(
                        referralCode
                    );

            }

        }


        await firebase.signOut(
            auth
        );


        location.replace(
            destination
        );


    } catch (error) {

        if (auth.currentUser) {

            await firebase
                .signOut(auth)
                .catch(() => {});

        }


        const errorCode =
            typeof error?.code === 'string'
                ? error.code
                : null;


        const safeCode =
            errorCode &&
            /^auth\/[a-z0-9-]+$/.test(
                errorCode
            )
                ? errorCode
                : null;


        if (
            errorCode &&
            messages[errorCode]
        ) {

            showMessage(
                messages[errorCode]
            );

        } else if (safeCode) {

            showMessage(
                `Unable to sign in (${safeCode}). Please try again or contact your administrator.`
            );

        } else {

            showMessage(
                'Unable to sign in. Please try again.'
            );

        }


        setBusy(false);

    }

}


/* =========================================================
   GOOGLE LOGIN
   ========================================================= */

const googleButton =
    byId('google-button');


if (googleButton) {

    googleButton.addEventListener(
        'click',
        () => {

            authenticate(
                googleButton,
                () =>
                    firebase.signInWithPopup(
                        auth,
                        provider
                    )
            );

        }
    );

}


/* =========================================================
   EMAIL LOGIN
   ========================================================= */

const loginForm =
    byId('login-form');


if (loginForm) {

    loginForm.addEventListener(
        'submit',
        event => {

            event.preventDefault();


            const email =
                byId('email')
                    .value
                    .trim();


            const password =
                byId('password')
                    .value;


            authenticate(
                byId('login-button'),
                () =>
                    firebase.signInWithEmailAndPassword(
                        auth,
                        email,
                        password
                    )
            );

        }
    );

}


/* =========================================================
   PASSWORD VISIBILITY
   ========================================================= */

const togglePassword =
    byId('toggle-password');


if (togglePassword) {

    togglePassword.addEventListener(
        'click',
        () => {

            const password =
                byId('password');


            const show =
                password.type === 'password';


            password.type =
                show
                    ? 'text'
                    : 'password';


            togglePassword.textContent =
                show
                    ? 'Hide'
                    : 'Show';


            togglePassword.setAttribute(
                'aria-label',
                show
                    ? 'Hide password'
                    : 'Show password'
            );


            togglePassword.setAttribute(
                'aria-pressed',
                String(show)
            );

        }
    );

}


/* =========================================================
   PASSWORD RESET
   ========================================================= */

const resetForm =
    byId('reset-form');


if (resetForm) {

    resetForm.addEventListener(
        'submit',
        async event => {

            event.preventDefault();


            if (
                busy ||
                !auth
            ) {
                return;
            }


            const email =
                byId('reset-email')
                    .value
                    .trim();


            showMessage('');

            setBusy(
                true,
                byId('reset-button')
            );


            const confirmation =
                'If an account uses this email, a password reset link is on its way. Check your inbox and spam folder.';


            try {

                await firebase.sendPasswordResetEmail(
                    auth,
                    email
                );


                showMessage(
                    confirmation,
                    'success'
                );


            } catch (error) {

                /*
                 * Keep reset responses neutral
                 * to avoid account enumeration.
                 */

                if (
                    error.code ===
                    'auth/user-not-found'
                ) {

                    showMessage(
                        confirmation,
                        'success'
                    );

                } else {

                    showMessage(
                        messages[error.code] ||
                        'Unable to send the reset email. Please try again.',
                        'error'
                    );

                }

            } finally {

                setBusy(false);

            }

        }
    );

}


/* =========================================================
   HISTORY RESTORATION
   ========================================================= */

window.addEventListener(
    'pageshow',
    event => {

        if (event.persisted) {

            location.reload();

        }

    }
);