'use strict';

const form = document.getElementById('login-form');
const passwordInput = document.getElementById('password');
const submitButton = document.getElementById('submit');
const errorText = document.getElementById('error');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  errorText.textContent = '';
  submitButton.disabled = true;
  try {
    const response = await fetch('/login', {
      method: 'POST',
      body: new URLSearchParams({ password: passwordInput.value }),
    });
    if (response.ok) {
      location.replace('/');
      return;
    }
    if (response.status === 429) {
      const seconds = response.headers.get('Retry-After') || 'a few';
      errorText.textContent = `Too many attempts. Try again in ${seconds} s.`;
    } else if (response.status === 401) {
      errorText.textContent = 'Wrong password.';
      passwordInput.select();
    } else {
      errorText.textContent = `Login failed (${response.status}).`;
    }
  } catch (_) {
    errorText.textContent = 'Could not reach the device.';
  } finally {
    submitButton.disabled = false;
  }
});
