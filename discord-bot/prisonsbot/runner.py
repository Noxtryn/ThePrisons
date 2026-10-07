"""The long-running part: answers slash commands. Needs discord.py (requirements.txt). The token is only passed to the library."""
import asyncio

from . import commands as CM
from . import config as C


def build_client(cfg: C.Config):
    """Creates the client and its command tree without connecting (testable)."""
    import discord
    from discord import app_commands

    client = discord.Client(intents=discord.Intents.none())
    tree = app_commands.CommandTree(client)

    def make_callback(name):
        async def callback(interaction: discord.Interaction):
            await interaction.response.defer()
            embed = await asyncio.to_thread(CM.handle, name, interaction.locale, cfg)
            await interaction.followup.send(embed=discord.Embed.from_dict(embed))
        return callback

    for c in CM.COMMANDS:
        tree.add_command(app_commands.Command(name=c["name"], description=c["description"], callback=make_callback(c["name"])))
    return client, tree


def run(cfg: C.Config) -> int:
    token = cfg.token  # raises ConfigError before anything starts
    import discord

    client, _tree = build_client(cfg)
    try:
        client.run(token, log_handler=None)
    except discord.LoginFailure:
        print("login failed: DISCORD_BOT_TOKEN was refused by Discord")
        return 1
    return 0
