"""Gateway listener with persistent role controls and private community commands."""
import asyncio
import io
import json
import os
from . import commands as CM, config as C, community as V, migration as M, nexora as N
from .api import Discord, DiscordError
from .store import Store


def build_client(cfg):
    import discord
    from discord import app_commands
    class Client(discord.Client):
        async def setup_hook(self):
            if self.user and str(self.user.id) != N.APPLICATION_ID:
                raise C.ConfigError('Bot token belongs to another Discord application')
            self.add_view(ModsView())
            if cfg.get('NEXORA_HEALTH_FILE'):
                self.health_task = asyncio.create_task(self.health_loop())
        async def health_loop(self):
            from .hosting import state
            while not self.is_closed():
                ready = self.is_ready()
                guild_present = ready and self.get_guild(int(N.guild_id(cfg))) is not None
                state(cfg.get('NEXORA_HEALTH_FILE'), 'online' if guild_present else 'guild-missing' if ready else 'connecting', pid=os.getpid(), application_id=N.APPLICATION_ID,
                      guild_id=cfg.get('DISCORD_GUILD_ID'), latency_seconds=self.latency if self.is_ready() else None)
                await asyncio.sleep(30)
        async def close(self):
            if getattr(self, 'health_task', None):
                self.health_task.cancel()
            await super().close()
    client = Client(intents=discord.Intents(guilds=True))
    tree = app_commands.CommandTree(client)
    locks, previews = {}, {}
    choices = [app_commands.Choice(name=N.project(k)['name'], value=k) for k in N.PROJECTS]
    def lock(key):
        return locks.setdefault(key, asyncio.Lock())
    def check(interaction, write=False, staff=False):
        if not interaction.guild or str(interaction.guild_id) != N.guild_id(cfg):
            raise C.ConfigError('Nur im konfigurierten Community-Server verfügbar.')
        if write and cfg.get('NEXORA_COMMUNITY_ENABLED') != 'true':
            raise C.ConfigError('Community-Änderungen sind bis zur Migration-Freigabe gesperrt.')
        if staff and not interaction.permissions.manage_guild:
            raise C.ConfigError('Manage Server permission is required')
    def api():
        return Discord(cfg.token)
    async def error(interaction, exc):
        text = C.redact(str(exc), cfg.get('DISCORD_BOT_TOKEN', ''))[:1500]
        sender = interaction.followup.send if interaction.response.is_done() else interaction.response.send_message
        await sender(text, ephemeral=True, allowed_mentions=discord.AllowedMentions.none())
    @tree.error
    async def command_error(interaction, exc):
        original = getattr(exc, 'original', exc)
        await error(interaction, original)
    async def change(interaction, selected=None, toggle=None):
        try:
            check(interaction, write=True)
            await interaction.response.defer(ephemeral=True)
            async with lock((interaction.guild_id, interaction.user.id)):
                member = await interaction.guild.fetch_member(interaction.user.id)
                ids = {k: N.setting(cfg, 'ROLE', k) for k in N.PROJECTS}
                roles = {str(r.id): r for r in await interaction.guild.fetch_roles()}
                for rid in ids.values():
                    role = roles.get(rid)
                    if not role or role.managed or role.permissions.value or role >= interaction.guild.me.top_role:
                        raise C.ConfigError('Mod-Rollen müssen unverwaltet, rechtefrei und unter der Bot-Rolle sein.')
                current = {str(r.id) for r in member.roles}
                if toggle:
                    selected = {k for k, v in ids.items() if v in current}
                    selected.symmetric_difference_update({toggle})
                add, remove = N.selection_delta(current, selected or [], ids)
                for rid in sorted(add):
                    await member.add_roles(roles[rid], reason='Nexora subscription', atomic=True)
                for rid in sorted(remove):
                    await member.remove_roles(roles[rid], reason='Nexora subscription', atomic=True)
            await interaction.followup.send('✅ Mod-Auswahl gespeichert.', ephemeral=True)
        except (C.ConfigError, discord.HTTPException) as exc:
            await error(interaction, exc)
    class ModsSelect(discord.ui.Select):
        def __init__(self):
            super().__init__(custom_id='nexora:v2:mods:select', placeholder='Eine, beide oder keine Mod auswählen', min_values=0, max_values=2,
                options=[discord.SelectOption(label=N.project(k)['name'], value=k) for k in N.PROJECTS])
        async def callback(self, interaction):
            await change(interaction, selected=self.values)
    class ModsView(discord.ui.View):
        def __init__(self):
            super().__init__(timeout=None)
            self.add_item(ModsSelect())
        @discord.ui.button(label='ThePrisons', custom_id='nexora:v2:mods:theprisons', style=discord.ButtonStyle.secondary)
        async def prisons(self, interaction, button):
            await change(interaction, toggle='theprisons')
        @discord.ui.button(label='Sky Supra', custom_id='nexora:v2:mods:sky-supra', style=discord.ButtonStyle.secondary)
        async def sky(self, interaction, button):
            await change(interaction, toggle='sky-supra')
        @discord.ui.button(label='Keine Mods', custom_id='nexora:v2:mods:none', style=discord.ButtonStyle.secondary)
        async def none(self, interaction, button):
            await change(interaction, selected=[])
    async def mods(interaction: discord.Interaction):
        try:
            check(interaction)
            await interaction.response.send_message(embed=discord.Embed.from_dict(N.embed('🎮 Deine Mods', 'Wähle ThePrisons, Sky Supra oder beide. Die Auswahl steuert deine Mod-Kanäle.', cfg=cfg)), view=ModsView(), ephemeral=True)
        except C.ConfigError as exc:
            await error(interaction, exc)
    tree.add_command(app_commands.Command(name='mods', description='Wähle deine Mods', callback=mods))
    def scoped(name):
        async def callback(interaction: discord.Interaction, mod: str):
            try:
                check(interaction, write=name in ('support', 'bug', 'suggest'))
                N.project(mod)
                await interaction.response.defer(ephemeral=True)
                if name in ('support', 'bug', 'suggest'):
                    async with lock((interaction.guild_id, interaction.user.id, mod, name)):
                        store = Store(cfg.get('NEXORA_STATE_DB', 'state/nexora.sqlite3'))
                        cid = await asyncio.to_thread(V.create_ticket, api(), cfg, mod, name, interaction.user.id, store)
                    result = N.embed('🛠️ ' + N.project(mod)['name'], f'Dein privater {name}-Bereich: <#{cid}>\nBitte nenne Version, Schritte und erwartetes Ergebnis.', mod, cfg=cfg)
                else:
                    try:
                        data = await asyncio.to_thread(V.release_data, cfg, mod)
                    except (OSError, ValueError):
                        data = None
                    result = V.answer(cfg, name, mod, data, str(interaction.locale))
                await interaction.followup.send(embed=discord.Embed.from_dict(result), ephemeral=True, allowed_mentions=discord.AllowedMentions.none())
            except (C.ConfigError, discord.HTTPException, OSError, ValueError) as exc:
                await error(interaction, exc)
        return app_commands.choices(mod=choices)(callback)
    for name in ('download', 'changelog', 'support', 'bug', 'suggest'):
        tree.add_command(app_commands.Command(name=name, description=next(c['description'] for c in CM.COMMANDS if c['name'] == name), callback=scoped(name)))
    async def status(interaction: discord.Interaction):
        try:
            check(interaction)
            enabled = cfg.get('NEXORA_COMMUNITY_ENABLED') == 'true'
            await interaction.response.send_message(embed=discord.Embed.from_dict(N.embed('⚙️ Nexora Core', 'Community: ' + ('aktiv' if enabled else 'Freigabe ausstehend') + '\nThePrisons · Sky Supra', cfg=cfg)), ephemeral=True)
        except C.ConfigError as exc:
            await error(interaction, exc)
    tree.add_command(app_commands.Command(name='status', description='Community-Bot-Status', callback=status))
    setup = app_commands.Group(name='setup', description='Servermigration', default_permissions=discord.Permissions(manage_guild=True), guild_only=True)
    @setup.command(name='preview', description='Nur lesen: Migrationsplan erstellen')
    async def preview(interaction: discord.Interaction):
        try:
            check(interaction, staff=True)
            await interaction.response.defer(ephemeral=True)
            result = M.plan(await asyncio.to_thread(M.snapshot, api(), cfg), cfg)
            previews[interaction.guild_id] = result
            await interaction.followup.send(f"Dry-Run: {len(result['changes'])} Änderungen. Plan: `{result['digest']}`",
                file=discord.File(io.BytesIO(json.dumps(result, ensure_ascii=False, indent=2).encode()), filename='nexora-plan.json'), ephemeral=True)
        except (C.ConfigError, OSError, ValueError) as exc:
            await error(interaction, exc)
    @setup.command(name='apply', description='Explizit freigegebenen Plan anwenden')
    async def apply(interaction: discord.Interaction, approval: str):
        try:
            check(interaction, staff=True)
            await interaction.response.defer(ephemeral=True)
            async with lock((interaction.guild_id, 'setup')):
                result = previews.get(interaction.guild_id)
                if not result:
                    raise C.ConfigError('Zuerst /setup preview ausführen')
                updated = await asyncio.to_thread(M.apply, api(), cfg, result, approval)
                previews.pop(interaction.guild_id, None)
            await interaction.followup.send('Migration angewendet. IDs als Konfiguration speichern und Onboarding prüfen.',
                file=discord.File(io.BytesIO(json.dumps(updated, indent=2).encode()), filename='nexora-result.json'), ephemeral=True)
        except (C.ConfigError, OSError, ValueError) as exc:
            await error(interaction, exc)
    tree.add_command(setup)
    def legacy(name):
        async def callback(interaction: discord.Interaction):
            try:
                check(interaction)
                await interaction.response.defer(ephemeral=True)
                result = await asyncio.to_thread(CM.handle, name, interaction.locale, cfg)
                result['author']['name'] = 'Nexora Core · ThePrisons'
                result['color'] = N.project('theprisons')['color']
                await interaction.followup.send(embed=discord.Embed.from_dict(result), ephemeral=True)
            except (C.ConfigError, discord.HTTPException) as exc:
                await error(interaction, exc)
        return callback
    for name in ('version', 'roadmap'):
        tree.add_command(app_commands.Command(name=name, description=name, callback=legacy(name)))
    return client, tree


def run(cfg):
    token = cfg.token
    N.guild_id(cfg)
    if cfg.get('DISCORD_APPLICATION_ID', N.APPLICATION_ID) != N.APPLICATION_ID:
        raise C.ConfigError('DISCORD_APPLICATION_ID must match Nexora Core')
    import discord
    client, _ = build_client(cfg)
    try:
        client.run(token, log_handler=None)
    except discord.LoginFailure:
        print('login failed: DISCORD_BOT_TOKEN was refused by Discord')
        return 2
    return 0
