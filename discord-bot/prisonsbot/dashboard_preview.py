"""Compile saved dashboard drafts into reviewable proposals; no live executor."""
import copy
import time
from . import migration, nexora as N
from .dashboard_core import DraftError, validate


def channel(state, identifier, types):
    found = next((c for c in state['channels'] if c['id'] == identifier), None)
    if not found or found['type'] not in types:
        raise DraftError('Der ausgewählte Kanal fehlt oder hat den falschen Typ.')
    return found


def safe_role(state, identifier):
    found = next((r for r in state['roles'] if r['id'] == identifier), None)
    bot_roles = state['bot_member'].get('roles', [])
    highest = max((r.get('position', 0) for r in state['roles'] if r['id'] in bot_roles), default=0)
    permissions = N.effective_permissions(state['guild_id'],
        {r['id']: int(r['permissions']) for r in state['roles']}, bot_roles, [])
    if not permissions & (1 << 28):
        raise DraftError('Dem Bot fehlt das Recht Rollen verwalten.')
    if not found or found['id'] == state['guild_id'] or found.get('managed'):
        raise DraftError('Diese Rolle kann nicht automatisch vergeben werden.')
    # Autoroles must never grant moderation/server management privileges.
    privileged = 8 | 2 | 4 | 16 | 32 | (1 << 28) | (1 << 40) | (1 << 13)
    if int(found['permissions']) & privileged or found['name'] in N.STAFF:
        raise DraftError('Team- oder Verwaltungsrollen dürfen keine Autorollen sein.')
    if found.get('position', 0) >= highest:
        raise DraftError('Die Bot-Rolle muss über der ausgewählten Rolle stehen.')
    return found


def compile_preview(saved, state=None, cfg=None, now=None):
    scope, module = saved['scope'], saved['module']
    document = validate(module, saved['document'])
    settings = document['settings']
    result = {'schema': 'nexora-dashboard-review-v1', 'scope': scope, 'module': module,
              'revision': saved['revision'], 'document_digest': migration.digest(document),
              'created_at': time.time() if now is None else now, 'live_apply_available': False,
              'simulation': state is None, 'blocking': [], 'changes': [],
              'snapshot_digest': migration.digest(state) if state else None}
    if module == 'messages':
        embed = N.embed(settings['title'], settings['description'],
                        scope if scope != 'community' else None, key='dashboard-preview')
        embed['footer'] = {'text': settings['footer']}
        if settings['image_url']:
            embed['image'] = {'url': settings['image_url']}
        result['payload'] = {'embeds': [embed], 'allowed_mentions': {'parse': []}}
        result['changes'].append({'action': 'prepare_message', 'channel_id': settings['channel_id'], 'enabled': document['enabled']})
        if not settings['channel_id']:
            result['blocking'].append('Bitte einen Zielkanal auswählen.')
        elif state:
            channel(state, settings['channel_id'], (0, 5))
    elif module == 'roles':
        result['changes'].append({'action': 'configure_autorole', **settings, 'enabled': document['enabled']})
        if scope != 'community' and settings['member_role']:
            result['blocking'].append('Die gemeinsame Member-Autorolle muss im Bereich Community konfiguriert werden.')
        if document['enabled'] and not settings['member_role']:
            result['blocking'].append('Bitte eine Member-Rolle auswählen.')
        if state and settings['member_role']:
            safe_role(state, settings['member_role'])
        result['notes'] = ['Mod-Rollen bleiben freiwillig wählbar; diese Regel vergibt keine Mod-Abos.',
                           'Verzögerung und Mitgliedschaftsprüfung werden im späteren Rollen-Worker durchgesetzt.']
    elif module == 'tickets':
        if scope == 'community':
            result['blocking'].append('Bitte Tickets einer der beiden Mods zuordnen.')
        result['changes'].append({'action': 'configure_private_tickets', **settings, 'enabled': document['enabled']})
        if state:
            channel(state, settings['category_id'], (4,))
            known = {r['id'] for r in state['roles']}
            for role in filter(None, (r.strip() for r in settings['staff_role_ids'].split(','))):
                if role not in known or role == state['guild_id']:
                    raise DraftError('Eine Teamrolle fehlt oder ist @everyone.')
                found = next(r for r in state['roles'] if r['id'] == role)
                if found['name'] in ('ThePrisons', 'Sky Supra', 'Member', 'Release Alerts'):
                    raise DraftError('Community-/Mod-Rollen dürfen keinen pauschalen privaten Ticketzugang erhalten.')
        result['notes'] = ['Privater Zugriff nur für Ersteller, Bot und freigegebenes Team.',
                           'Aufbewahrung ist eine geplante Einstellung; sie löst keine Löschung aus.']
    elif module == 'releases':
        if scope == 'community':
            result['blocking'].append('Releases gehören zu ThePrisons oder Sky Supra.')
        for key in ('version', 'download_url', 'channel_id'):
            if document['enabled'] and not settings[key]:
                result['blocking'].append('Release-Feld fehlt: ' + key)
        if state and settings['channel_id']:
            channel(state, settings['channel_id'], (0, 5))
            # Prevent cross-mod delivery even when the channel exists.
            if cfg:
                expected = cfg.get('DISCORD_CHANNEL_' + scope.upper().replace('-', '_') + '_UPDATES')
                if not expected:
                    result['blocking'].append('Der Update-Kanal dieser Mod ist noch nicht zugeordnet.')
                elif expected != settings['channel_id']:
                    result['blocking'].append('Der Zielkanal stimmt nicht mit dem Update-Kanal dieser Mod überein.')
        result['changes'].append({'action': 'prepare_release', **settings, 'enabled': document['enabled']})
        if scope in N.PROJECTS:
            result['payload'] = {'embeds': [N.embed(N.project(scope)['name'] + ' · ' + (settings['version'] or 'Release-Entwurf'),
                         settings['changelog'], scope, key='release-preview')], 'allowed_mentions': {'parse': []}}
        result['notes'] = ['Eine Vorschau veröffentlicht kein Release und sendet keine Benachrichtigung.']
    elif module == 'setup':
        pattern = settings['channel_pattern']
        if pattern.count('{name}') != 1 or '{' in pattern.replace('{name}', '') or '}' in pattern.replace('{name}', ''):
            raise DraftError('Die Kanalvorlage muss genau einmal {name} enthalten.')
        for key in ('start_category', 'community_category', 'support_category'):
            if not settings[key].strip() or len(settings[key]) > 100:
                raise DraftError('Kategorienamen müssen 1 bis 100 Zeichen enthalten.')
        if scope != 'community':
            result['blocking'].append('Die Serverstruktur wird im Bereich Community verwaltet.')
        if state and cfg:
            base = migration.plan(state, cfg)
            result['blocking'].extend(base['blocking'])
            changes = copy.deepcopy(base['changes'])
            categories = {'start-here': settings['start_category'], 'community': settings['community_category'],
                          'support-development': settings['support_category']}
            category_names = [categories.get(s['key'], s['name']) for s in N.channels() if not s['parent']]
            if len(set(category_names)) != len(category_names):
                result['blocking'].append('Kategorienamen müssen eindeutig sein.')
            existing = {c['id']: c for c in state['channels']}
            for spec in N.channels():
                target_id = base['channels'][spec['key']]
                desired = categories.get(spec['key'], spec['name']) if not spec['parent'] else pattern.replace('{name}', spec['key'].removeprefix('theprisons-').removeprefix('sky-supra-'))
                if len(desired) > 100:
                    raise DraftError('Die Kanalvorlage erzeugt einen zu langen Namen.')
                old = existing.get(target_id)
                if any(c['name'] == desired and c['id'] != target_id and c['type'] == 4 for c in state['channels']) and not spec['parent']:
                    result['blocking'].append('Kategorie bereits anders zugeordnet: ' + desired)
                change = next((c for c in changes if c['kind'] == 'channel' and c['key'] == spec['key']), None)
                if change:
                    change['body']['name'] = desired
                elif old and old['name'] != desired:
                    changes.append({'kind': 'channel', 'key': spec['key'], 'id': target_id,
                                    'body': {'name': desired}, 'before': {'name': old['name']}})
            result['changes'] = changes
            result['permission_matrix'] = base['permission_matrix']
            result['notes'] = ['Bestehende Kanal-IDs bleiben erhalten. Keine Löschoperation.',
                               'Diese Dashboard-Vorschau ist kein freigegebener CLI-Migrationsplan.']
        else:
            result['changes'] = [{'action': 'prepare_server_structure', **settings}]
    if state is None:
        result['blocking'].append('Simulation: vorhandene Rollen, Kanäle und Bot-Rechte wurden nicht geprüft.')
    result['digest'] = migration.digest(result)
    return result
