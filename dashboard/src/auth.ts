import { UserManager, WebStorageStateStore } from 'oidc-client-ts';

export const userManager = new UserManager({
  authority: 'http://localhost:8180/realms/payments',
  client_id: 'dashboard',
  redirect_uri: `${window.location.origin}/`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid',
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  automaticSilentRenew: false,
});

/** Finishes a redirect from Keycloak if there is one, otherwise starts the login. */
export async function login() {
  const params = new URLSearchParams(window.location.search);
  if (params.has('code') && params.has('state')) {
    const user = await userManager.signinRedirectCallback();
    window.history.replaceState({}, '', '/');
    return user;
  }
  const user = await userManager.getUser();
  if (user && !user.expired) {
    return user;
  }
  await userManager.signinRedirect();
  return null;
}
