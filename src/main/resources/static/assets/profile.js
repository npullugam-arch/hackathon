import { request, post, showMessage } from './api.js';
import './advertisements.js';
import {currentUser} from './session-user.js';


/* =========================================================
   HELPERS
   ========================================================= */

const byId = id => document.getElementById(id);

let leaving = false;


/* =========================================================
   BROADCAST LOGOUT
   ========================================================= */

const channel =
    'BroadcastChannel' in window
        ? new BroadcastChannel('launchpad-auth')
        : null;


if (channel) {

    channel.onmessage = event => {

        if (event.data === 'logout') {
            location.replace('/login');
        }

    };

}


/* =========================================================
   COPY ACCOUNT ID
   ========================================================= */

async function copyAccountId() {

    const uid = byId('uid')?.textContent?.trim();

    if (!uid || uid === '—') {
        return;
    }

    try {

        await navigator.clipboard.writeText(uid);

        showTemporaryCopyMessage();

    } catch {

        const textarea = document.createElement('textarea');

        textarea.value = uid;

        document.body.appendChild(textarea);

        textarea.select();

        document.execCommand('copy');

        textarea.remove();

        showTemporaryCopyMessage();
    }
}


function showTemporaryCopyMessage() {

    const message = byId('message');

    if (!message) return;

    message.hidden = false;

    message.textContent = 'Account ID copied successfully.';

    message.style.background = '#eaf8f0';
    message.style.color = '#16845a';
    message.style.borderColor = '#c9ecd9';

    clearTimeout(window.copyMessageTimer);

    window.copyMessageTimer = setTimeout(() => {

        message.hidden = true;

    }, 2200);
}


/* =========================================================
   SET PROFILE DATA
   ========================================================= */

function populateProfile(user) {

    const name =
        user.name ||
        user.email?.split('@')[0] ||
        'there';


    const firstName =
        name
            .trim()
            .split(/\s+/)[0];


    /* --------------------------------
       Greeting
    -------------------------------- */

    byId('greeting').textContent =
        `, ${firstName}`;


    /* --------------------------------
       Identity
    -------------------------------- */

    byId('name').textContent =
        name;

    byId('email').textContent =
        user.email ||
        'No email provided';


    /* --------------------------------
       Avatar
    -------------------------------- */

    byId('initials').textContent =
        name
            .slice(0, 1)
            .toUpperCase();


    if (user.photoUrl) {

        const avatar =
            byId('avatar');

        avatar.onload = () => {

            avatar.hidden = false;

            byId('initials').hidden = true;

        };


        avatar.onerror = () => {

            avatar.hidden = true;

            byId('initials').hidden = false;

        };


        try {

            const url =
                new URL(user.photoUrl);


            if (
                url.protocol === 'https:' &&
                url.hostname.endsWith(
                    '.googleusercontent.com'
                )
            ) {

                avatar.src =
                    url.href;

            }

        } catch {

            avatar.hidden = true;

            byId('initials').hidden = false;

        }

    }


    /* --------------------------------
       Provider
    -------------------------------- */

    let provider = 'Firebase';


    if (
        user.provider === 'google.com'
    ) {

        provider = 'Google';

    } else if (
        user.provider === 'password'
    ) {

        provider = 'Email & password';

    }


    /* --------------------------------
       Verification
    -------------------------------- */

    const verified =
        user.emailVerified
            ? 'Verified'
            : 'Not verified';


    /* --------------------------------
       Dates
    -------------------------------- */

    const created =
        user.createdAt
            ? new Date(
                user.createdAt
            ).toLocaleDateString(
                undefined,
                {
                    dateStyle: 'medium'
                }
            )
            : '—';


    const lastLogin =
        user.lastSignInAt
            ? new Date(
                user.lastSignInAt
            ).toLocaleString(
                undefined,
                {
                    dateStyle: 'medium',
                    timeStyle: 'short'
                }
            )
            : '—';


    /* =====================================================
       QUICK INFO
    ===================================================== */

    byId('verified').textContent =
        verified;

    byId('created').textContent =
        created;

    byId('last-login').textContent =
        lastLogin;

    byId('uid').textContent =
        user.uid || '—';


    /* =====================================================
       ACCOUNT DETAILS
    ===================================================== */

    byId('detail-name').textContent =
        name;

    byId('detail-email').textContent =
        user.email ||
        'No email provided';

    byId('detail-provider').textContent =
        provider;

    byId('detail-created').textContent =
        created;

    byId('detail-login').textContent =
        lastLogin;

    byId('detail-uid').textContent =
        user.uid || '—';


    /* =====================================================
       CONNECTED ACCOUNT
    ===================================================== */

    const connectedProvider =
        byId('connected-provider');

    if (connectedProvider) {

        connectedProvider.textContent =
            provider === 'Google'
                ? 'Signed in with Google'
                : `Signed in with ${provider}`;

    }

}


/* =========================================================
   LOAD PROFILE
   ========================================================= */

async function loadProfile() {

    try {

        const user =
            await currentUser();


        populateProfile(user);


        /* Hide loading */

        byId('profile-loading').hidden =
            true;


        /* Show content */

        byId('dashboard-content').hidden =
            false;


    } catch (error) {

        /* --------------------------------
           Unauthorized
        -------------------------------- */

        if (error.status === 401) {

            location.replace('/login');

            return;

        }


        /* --------------------------------
           Error
        -------------------------------- */

        byId('profile-loading').hidden =
            true;


        showMessage(
            error.status === 503
                ? error.message
                : 'We couldn’t load your account. Check your connection and refresh the page.'
        );

    }

}


/* =========================================================
   LOGOUT
   ========================================================= */

byId('logout').addEventListener(
    'click',
    async () => {

        if (leaving) {
            return;
        }


        leaving = true;


        const button =
            byId('logout');


        button.disabled =
            true;


        button.innerHTML =
            '<span>Logging out…</span>';


        try {

            await post(
                '/api/auth/logout'
            );


            channel?.postMessage(
                'logout'
            );


            location.replace(
                '/?loggedOut=1'
            );


        } catch (error) {

            showMessage(
                error.message
            );


            leaving = false;


            button.disabled =
                false;


            button.innerHTML =
                '<span>Try logging out again</span>';

        }

    }
);


/* =========================================================
   COPY BUTTONS
   ========================================================= */

byId('copyUid')?.addEventListener(
    'click',
    copyAccountId
);


byId('copyUidBottom')?.addEventListener(
    'click',
    copyAccountId
);


/* =========================================================
   EDIT PROFILE
   ========================================================= */

document
    .querySelector('.edit-profile-button')
    ?.addEventListener(
        'click',
        () => {

            /*
             * Your backend currently doesn't expose
             * an edit-profile endpoint.
             *
             * Keep this button ready for the future.
             */

            showTemporaryEditMessage();

        }
    );


function showTemporaryEditMessage() {

    const message =
        byId('message');

    if (!message) return;

    message.hidden =
        false;

    message.textContent =
        'Profile editing will be available soon.';

    message.style.background =
        '#f0edff';

    message.style.color =
        '#6045d2';

    message.style.borderColor =
        '#ddd5ff';


    clearTimeout(
        window.editMessageTimer
    );


    window.editMessageTimer =
        setTimeout(() => {

            message.hidden =
                true;

        }, 2500);

}


/* =========================================================
   TAB / HISTORY RECHECK
   ========================================================= */

document.addEventListener(
    'visibilitychange',
    () => {

        if (
            !document.hidden &&
            !leaving
        ) {

            loadProfile();

        }

    }
);


window.addEventListener(
    'pageshow',
    event => {

        if (event.persisted) {

            location.reload();

        }

    }
);


/* =========================================================
   INITIAL LOAD
   ========================================================= */

loadProfile();