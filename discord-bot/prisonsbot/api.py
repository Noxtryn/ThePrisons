"""A tiny Discord REST client (standard library). Errors never contain the token."""
import json
import time
import urllib.error
import urllib.request

from .config import USER_AGENT

API = "https://discord.com/api/v10"


class DiscordError(Exception):
    def __init__(self, status: int, message: str, code=None):
        super().__init__(f"Discord API {status}: {message}")
        self.status = status
        self.code = code


def _urllib_transport(method, url, headers, data):
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except urllib.error.URLError as error:
        raise DiscordError(0, f"network error ({error.reason})") from None


class Discord:
    def __init__(self, token: str, transport=None, sleep=time.sleep):
        self._token = token
        self._transport = transport or _urllib_transport
        self._sleep = sleep

    def request(self, method, path, body=None):
        headers = {"Authorization": "Bot " + self._token, "User-Agent": USER_AGENT, "Content-Type": "application/json"}
        data = None if body is None else json.dumps(body).encode("utf-8")
        for _ in range(4):
            status, raw = self._transport(method, API + path, headers, data)
            try:
                parsed = json.loads(raw.decode("utf-8")) if raw else None
            except ValueError:
                parsed = None
            if status == 429:
                self._sleep(min(float((parsed or {}).get("retry_after", 1)), 30))
                continue
            if status >= 300:
                message = (parsed or {}).get("message", "request failed") if isinstance(parsed, dict) else "request failed"
                code = (parsed or {}).get("code") if isinstance(parsed, dict) else None
                raise DiscordError(status, str(message), code)
            return parsed
        raise DiscordError(429, "rate limited")

    def me(self):
        return self.request("GET", "/users/@me")

    def channel_messages(self, channel_id, limit=100):
        return self.request("GET", f"/channels/{channel_id}/messages?limit={limit}") or []

    def post(self, channel_id, payload):
        return self.request("POST", f"/channels/{channel_id}/messages", payload)

    def edit(self, channel_id, message_id, payload):
        return self.request("PATCH", f"/channels/{channel_id}/messages/{message_id}", payload)

    def register_commands(self, application_id, guild_id, commands):
        path = f"/applications/{application_id}/guilds/{guild_id}/commands" if guild_id else f"/applications/{application_id}/commands"
        return self.request("PUT", path, commands)
