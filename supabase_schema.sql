-- ============================================================================
-- Vanguard RTX v0.10beta — Supabase Cloud Multiplayer, Security & Skins Schema
-- Paste this entire script into Supabase Dashboard -> SQL Editor -> Run.
-- Also set: Authentication -> Providers -> Email -> Confirm email = OFF
-- ============================================================================

create extension if not exists pgcrypto;

-- ----------------------------------------------------------------------------
-- S0. Server-Only Master Key Vault (never exposed to PostgREST / anon / auth)
-- ----------------------------------------------------------------------------
create schema if not exists private_security;
revoke all on schema private_security from public, anon, authenticated;

create table if not exists private_security.server_keys (
    id integer primary key default 1 check (id = 1),
    aes_key text not null default encode(gen_random_bytes(32), 'hex'),
    hmac_key bytea not null default gen_random_bytes(32),
    created_at timestamptz not null default now()
);

revoke all on all tables in schema private_security from public, anon, authenticated;

insert into private_security.server_keys (id)
values (1)
on conflict (id) do nothing;

create or replace function private_security.normalize_device_token(p_raw text)
returns text
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_clean text := lower(left(trim(coalesce(p_raw, '')), 128));
    v_hmac_key bytea;
begin
    if v_clean = '' then
        return '';
    end if;
    -- Already a server-derived HMAC device token ('srv1:' + 64 hex chars)
    if v_clean ~ '^srv1:[0-9a-f]{64}$' then
        return v_clean;
    end if;

    select hmac_key into v_hmac_key
    from private_security.server_keys
    where id = 1;

    if v_hmac_key is null then
        insert into private_security.server_keys (id) values (1) on conflict (id) do nothing;
        select hmac_key into v_hmac_key from private_security.server_keys where id = 1;
    end if;

    return 'srv1:' || encode(hmac(convert_to(v_clean, 'utf8'), v_hmac_key, 'sha256'), 'hex');
end;
$$;

create or replace function private_security.seal_text(p_plain text)
returns text
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_aes_key text;
    v_clean text := coalesce(p_plain, '');
begin
    if v_clean = '' then
        return '';
    end if;
    select aes_key into v_aes_key
    from private_security.server_keys
    where id = 1;

    if v_aes_key is null then
        insert into private_security.server_keys (id) values (1) on conflict (id) do nothing;
        select aes_key into v_aes_key from private_security.server_keys where id = 1;
    end if;

    return 'enc1:' || encode(pgp_sym_encrypt(v_clean, v_aes_key, 'cipher-algo=aes256'), 'base64');
end;
$$;

create or replace function private_security.unseal_text(p_cipher text)
returns text
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_aes_key text;
    v_raw text := coalesce(p_cipher, '');
begin
    if v_raw = '' then
        return '';
    end if;
    if left(v_raw, 5) <> 'enc1:' then
        return v_raw;
    end if;
    select aes_key into v_aes_key
    from private_security.server_keys
    where id = 1;
    if v_aes_key is null then
        return '';
    end if;
    return pgp_sym_decrypt(decode(substr(v_raw, 6), 'base64'), v_aes_key);
exception when others then
    return '';
end;
$$;

revoke all on function private_security.normalize_device_token(text) from public, anon, authenticated;
revoke all on function private_security.seal_text(text) from public, anon, authenticated;
revoke all on function private_security.unseal_text(text) from public, anon, authenticated;

-- ----------------------------------------------------------------------------
-- 0. Global App Version Policy (enforces exact latest build v0.10beta!)
-- ----------------------------------------------------------------------------
create table if not exists public.app_config (
    id integer primary key default 1 check (id = 1),
    min_version_code integer not null default 10,
    latest_version_name text not null default '0.10beta',
    update_message text not null default 'Доступна только последняя версия Vanguard RTX v0.10beta. Обновите игру, чтобы войти или зарегистрироваться.',
    updated_at timestamptz not null default now()
);

insert into public.app_config (id, min_version_code, latest_version_name, update_message)
values (
    1,
    10,
    '0.10beta',
    'Доступна только последняя версия Vanguard RTX v0.10beta. Обновите игру, чтобы войти или зарегистрироваться.'
)
on conflict (id) do update
set min_version_code = greatest(public.app_config.min_version_code, excluded.min_version_code),
    latest_version_name = case
        when public.app_config.min_version_code <= excluded.min_version_code then excluded.latest_version_name
        else public.app_config.latest_version_name
    end,
    update_message = case
        when public.app_config.min_version_code <= excluded.min_version_code then excluded.update_message
        else public.app_config.update_message
    end,
    updated_at = now();

-- 0b. Hardware / Device Bans (stores server-HMAC device tokens + encrypted ban reason)
create table if not exists public.banned_devices (
    device_hash text primary key check (char_length(device_hash) between 16 and 128),
    reason text not null default '' check (char_length(reason) <= 160),
    reason_enc text not null default '',
    banned_uid uuid,
    banned_short_id text not null default '',
    banned_at timestamptz not null default now()
);

alter table public.banned_devices add column if not exists reason_enc text not null default '';

-- 1. Player Profiles (unique 6-digit ID or reserved 'developer' admin ID)
create table if not exists public.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    short_id text not null unique check (short_id ~ '^[1-9][0-9]{5}$' or short_id = 'developer'),
    display_name text not null check (char_length(display_name) between 3 and 20),
    avatar_data text not null default 'preset:ghost' check (char_length(avatar_data) <= 65000),
    online boolean not null default true,
    last_seen_at timestamptz not null default now(),
    total_kills integer not null default 0 check (total_kills >= 0),
    total_headshots integer not null default 0 check (total_headshots >= 0),
    xp bigint not null default 0 check (xp >= 0),
    level integer not null default 1 check (level >= 1),
    coins integer not null default 0 check (coins >= 0),
    owned_skins jsonb not null default '[]'::jsonb,
    equipped_skins jsonb not null default '{}'::jsonb,
    banned boolean not null default false,
    ban_reason text not null default '' check (char_length(ban_reason) <= 120),
    last_device_hash text not null default '' check (char_length(last_device_hash) <= 128),
    encrypted_vault text not null default '',
    updated_at timestamptz not null default now()
);

-- Idempotent migrations for existing deployments
alter table public.profiles add column if not exists banned boolean not null default false;
alter table public.profiles add column if not exists ban_reason text not null default '';
alter table public.profiles add column if not exists last_seen_at timestamptz not null default now();
alter table public.profiles add column if not exists xp bigint not null default 0;
alter table public.profiles add column if not exists level integer not null default 1;
alter table public.profiles add column if not exists coins integer not null default 0;
alter table public.profiles add column if not exists owned_skins jsonb not null default '[]'::jsonb;
alter table public.profiles add column if not exists equipped_skins jsonb not null default '{}'::jsonb;
alter table public.profiles add column if not exists last_device_hash text not null default '';
alter table public.profiles add column if not exists encrypted_vault text not null default '';

alter table public.app_config enable row level security;
alter table public.banned_devices enable row level security;

drop policy if exists "app_config_public_read" on public.app_config;
create policy "app_config_public_read"
    on public.app_config for select
    to anon, authenticated
    using (true);

drop policy if exists "banned_devices_no_direct_access" on public.banned_devices;
create policy "banned_devices_no_direct_access"
    on public.banned_devices for select
    to authenticated
    using (false);

-- Update short_id check constraint to permit exclusive 'developer' admin ID
do $$
declare
    v_con record;
begin
    for v_con in
        select conname
        from pg_constraint
        where conrelid = 'public.profiles'::regclass
          and contype = 'c'
          and pg_get_constraintdef(oid) ilike '%short_id%'
    loop
        execute format('alter table public.profiles drop constraint if exists %I', v_con.conname);
    end loop;

    alter table public.profiles
        add constraint profiles_short_id_check
        check (short_id ~ '^[1-9][0-9]{5}$' or short_id = 'developer');
exception when duplicate_object then
    null;
end $$;

-- 2. Friend Requests
create table if not exists public.friend_requests (
    id uuid primary key default gen_random_uuid(),
    from_uid uuid not null references public.profiles(user_id) on delete cascade,
    to_uid uuid not null references public.profiles(user_id) on delete cascade,
    status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
    created_at timestamptz not null default now(),
    check (from_uid <> to_uid)
);

create index if not exists idx_friend_requests_to_uid on public.friend_requests(to_uid, status);
create index if not exists idx_friend_requests_from_uid on public.friend_requests(from_uid, status);

-- 3. Mutual Friendships
create table if not exists public.friendships (
    user_id uuid not null references public.profiles(user_id) on delete cascade,
    friend_uid uuid not null references public.profiles(user_id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, friend_uid),
    check (user_id <> friend_uid)
);

create index if not exists idx_friendships_friend_uid on public.friendships(friend_uid);

-- 4. Online Lobbies (1v1 / party + optional bots)
create table if not exists public.lobbies (
    id uuid primary key default gen_random_uuid(),
    host_uid uuid not null references public.profiles(user_id) on delete cascade,
    host_name text not null,
    map_id text not null default 'MIRAGE' check (map_id in ('MIRAGE', 'DUST2')),
    mode text not null default 'BOMB_DEFUSAL' check (mode in ('BOMB_DEFUSAL', 'TEAM_DEATHMATCH')),
    rounds_to_win integer not null default 8 check (rounds_to_win between 1 and 15),
    allow_bots boolean not null default false,
    status text not null default 'waiting' check (status in ('waiting', 'playing', 'finished')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_lobbies_status_updated on public.lobbies(status, updated_at);

-- 5. Lobby Members & Live Match Sync State
create table if not exists public.lobby_members (
    lobby_id uuid not null references public.lobbies(id) on delete cascade,
    user_id uuid not null references public.profiles(user_id) on delete cascade,
    short_id text not null,
    display_name text not null,
    avatar_data text not null default 'preset:ghost',
    level integer not null default 1,
    equipped_skins jsonb not null default '{}'::jsonb,
    team text not null default 'T' check (team in ('T', 'CT')),
    ready boolean not null default true,
    x double precision not null default 0,
    y double precision not null default 0,
    z double precision not null default 0,
    yaw_deg double precision not null default 0,
    pitch_deg double precision not null default 0,
    hp integer not null default 100 check (hp between 0 and 100),
    dead boolean not null default false,
    moving boolean not null default false,
    shooting boolean not null default false,
    weapon text not null default 'GLOCK_18',
    kills integer not null default 0,
    deaths integer not null default 0,
    hit_target_uid text not null default '',
    hit_damage integer not null default 0,
    hit_headshot boolean not null default false,
    hit_weapon text not null default '',
    hit_seq integer not null default 0,
    updated_at timestamptz not null default now(),
    primary key (lobby_id, user_id)
);

alter table public.lobby_members add column if not exists level integer not null default 1;
alter table public.lobby_members add column if not exists equipped_skins jsonb not null default '{}'::jsonb;

create index if not exists idx_lobby_members_lobby on public.lobby_members(lobby_id);

-- Anti-cheat validation trigger for live match telemetry
create or replace function public.enforce_lobby_member_anti_cheat()
returns trigger
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_prof public.profiles%rowtype;
    v_owned jsonb;
    v_sanitized_skins jsonb := '{}'::jsonb;
    v_weapon text;
    v_skin_id text;
    v_dt double precision;
    v_dist double precision;
begin
    -- 1. Anti-ban bypass: Banned accounts cannot participate in matches
    select * into v_prof from public.profiles where user_id = new.user_id;
    if found and v_prof.banned = true and v_prof.short_id <> 'developer' then
        raise exception 'PLAYER_BANNED: Аккаунт заблокирован';
    end if;

    -- 2. Anti-level spoofing: Player cannot spoof level in lobby
    if found then
        new.level := v_prof.level;
    end if;

    -- 3. Anti-skin spoofing: Only allow skins the player actually owns
    if found then
        v_owned := coalesce(v_prof.owned_skins, '[]'::jsonb);
        if new.equipped_skins is not null and jsonb_typeof(new.equipped_skins) = 'object' then
            for v_weapon, v_skin_id in select * from jsonb_each_text(new.equipped_skins) loop
                if v_skin_id = '' or (v_owned ? v_skin_id) or v_prof.short_id = 'developer' then
                    v_sanitized_skins := jsonb_set(v_sanitized_skins, array[v_weapon], to_jsonb(v_skin_id), true);
                end if;
            end loop;
            new.equipped_skins := v_sanitized_skins;
        end if;
    end if;

    -- 4. Anti-illegal weapon check
    if new.weapon not in ('AK47', 'M4A1_S', 'AWP', 'DESERT_EAGLE', 'GLOCK_18', 'USP_S', 'MP9', 'P90', 'KNIFE', 'C4_BOMB') then
        new.weapon := 'GLOCK_18';
    end if;

    -- 5. Anti-godmode / health clamp
    new.hp := least(greatest(coalesce(new.hp, 100), 0), 100);
    if new.hp = 0 then
        new.dead := true;
    end if;

    -- 6. Anti-teleport / speedhack validation (max plausible velocity including lag spikes: 38 m/s)
    if tg_op = 'UPDATE' and old is not null then
        if old.x <> 0 and old.z <> 0 and new.dead = false and old.dead = false then
            v_dt := extract(epoch from (now() - old.updated_at));
            if v_dt between 0.02 and 2.5 then
                v_dist := sqrt(power(new.x - old.x, 2) + power(new.z - old.z, 2));
                if (v_dist / v_dt) > 38.0 then
                    new.x := old.x;
                    new.z := old.z;
                end if;
            end if;
        end if;
    end if;

    -- 7. Hit telemetry bounds
    new.hit_damage := least(greatest(coalesce(new.hit_damage, 0), 0), 100);

    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_enforce_lobby_member_anti_cheat on public.lobby_members;
create trigger trg_enforce_lobby_member_anti_cheat
    before insert or update on public.lobby_members
    for each row
    execute function public.enforce_lobby_member_anti_cheat();

-- 6. Lobby Invites
create table if not exists public.lobby_invites (
    id uuid primary key default gen_random_uuid(),
    lobby_id uuid not null references public.lobbies(id) on delete cascade,
    from_uid uuid not null references public.profiles(user_id) on delete cascade,
    to_uid uuid not null references public.profiles(user_id) on delete cascade,
    map_id text not null default 'MIRAGE',
    status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
    created_at timestamptz not null default now(),
    check (from_uid <> to_uid)
);

create index if not exists idx_lobby_invites_to_uid on public.lobby_invites(to_uid, status);
create index if not exists idx_lobby_invites_lobby_target on public.lobby_invites(lobby_id, to_uid, status);

-- High-throughput partial indexes for live server queries
create index if not exists idx_profiles_online_true on public.profiles(online, last_seen_at) where online = true;
create index if not exists idx_profiles_banned_device on public.profiles(last_device_hash) where banned = true;

-- ============================================================================
-- Enable Row Level Security (RLS) on every table
-- ============================================================================
alter table public.profiles enable row level security;
alter table public.friend_requests enable row level security;
alter table public.friendships enable row level security;
alter table public.lobbies enable row level security;
alter table public.lobby_members enable row level security;
alter table public.lobby_invites enable row level security;

-- Profiles policies
drop policy if exists "profiles_select_auth" on public.profiles;
create policy "profiles_select_auth" on public.profiles
    for select to authenticated using (true);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
    for insert to authenticated with check (
        auth.uid() = user_id
        and short_id <> 'developer'
        and xp = 0
        and level = 1
        and coins = 0
        and banned = false
        and not exists (
            select 1 from public.banned_devices bd
            where bd.device_hash = private_security.normalize_device_token(
                coalesce(nullif(last_device_hash, ''), auth.jwt() -> 'user_metadata' ->> 'device_hash', '')
            )
        )
    );

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
    for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- Friend requests policies
drop policy if exists "friend_requests_select_involved" on public.friend_requests;
create policy "friend_requests_select_involved" on public.friend_requests
    for select to authenticated using (auth.uid() = from_uid or auth.uid() = to_uid);

drop policy if exists "friend_requests_insert_sender" on public.friend_requests;
create policy "friend_requests_insert_sender" on public.friend_requests
    for insert to authenticated with check (auth.uid() = from_uid and from_uid <> to_uid);

drop policy if exists "friend_requests_update_receiver" on public.friend_requests;
create policy "friend_requests_update_receiver" on public.friend_requests
    for update to authenticated using (auth.uid() = to_uid);

drop policy if exists "friend_requests_delete_involved" on public.friend_requests;
create policy "friend_requests_delete_involved" on public.friend_requests
    for delete to authenticated using (auth.uid() = from_uid or auth.uid() = to_uid);

-- Friendships policies
drop policy if exists "friendships_select_own" on public.friendships;
create policy "friendships_select_own" on public.friendships
    for select to authenticated using (auth.uid() = user_id);

-- Lobbies policies
drop policy if exists "lobbies_select_auth" on public.lobbies;
create policy "lobbies_select_auth" on public.lobbies
    for select to authenticated using (true);

drop policy if exists "lobbies_insert_host" on public.lobbies;
create policy "lobbies_insert_host" on public.lobbies
    for insert to authenticated with check (auth.uid() = host_uid);

drop policy if exists "lobbies_update_host" on public.lobbies;
create policy "lobbies_update_host" on public.lobbies
    for update to authenticated using (auth.uid() = host_uid) with check (auth.uid() = host_uid);

drop policy if exists "lobbies_delete_host" on public.lobbies;
create policy "lobbies_delete_host" on public.lobbies
    for delete to authenticated using (auth.uid() = host_uid);

-- Lobby members policies
drop policy if exists "lobby_members_select_auth" on public.lobby_members;
create policy "lobby_members_select_auth" on public.lobby_members
    for select to authenticated using (true);

drop policy if exists "lobby_members_insert_self" on public.lobby_members;
create policy "lobby_members_insert_self" on public.lobby_members
    for insert to authenticated with check (auth.uid() = user_id);

drop policy if exists "lobby_members_update_self" on public.lobby_members;
create policy "lobby_members_update_self" on public.lobby_members
    for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "lobby_members_delete_self_or_host" on public.lobby_members;
create policy "lobby_members_delete_self_or_host" on public.lobby_members
    for delete to authenticated using (
        auth.uid() = user_id
        or exists (select 1 from public.lobbies l where l.id = lobby_id and l.host_uid = auth.uid())
    );

-- Lobby invites policies
drop policy if exists "lobby_invites_select_involved" on public.lobby_invites;
create policy "lobby_invites_select_involved" on public.lobby_invites
    for select to authenticated using (auth.uid() = from_uid or auth.uid() = to_uid);

drop policy if exists "lobby_invites_insert_sender" on public.lobby_invites;
create policy "lobby_invites_insert_sender" on public.lobby_invites
    for insert to authenticated with check (auth.uid() = from_uid and from_uid <> to_uid);

drop policy if exists "lobby_invites_update_involved" on public.lobby_invites;
create policy "lobby_invites_update_involved" on public.lobby_invites
    for update to authenticated using (auth.uid() = to_uid or auth.uid() = from_uid);

drop policy if exists "lobby_invites_delete_involved" on public.lobby_invites;
create policy "lobby_invites_delete_involved" on public.lobby_invites
    for delete to authenticated using (auth.uid() = to_uid or auth.uid() = from_uid);

-- ============================================================================
-- Level & EXP Progression Formula (100 XP/kill, 500 base, +25%/lvl capped at 20)
-- ============================================================================
create or replace function public.calc_level_step_xp(p_level integer)
returns integer
language plpgsql
immutable
as $$
declare
    v_exp integer;
begin
    v_exp := least(greatest(coalesce(p_level, 1) - 1, 0), 18);
    return round(500.0 * power(1.25::double precision, v_exp::double precision))::integer;
end;
$$;

create or replace function public.calc_level_from_xp(p_xp bigint)
returns integer
language plpgsql
immutable
as $$
declare
    v_rem bigint := greatest(coalesce(p_xp, 0), 0);
    v_lvl integer := 1;
    v_step bigint;
begin
    loop
        v_step := public.calc_level_step_xp(v_lvl)::bigint;
        exit when v_rem < v_step or v_lvl >= 999;
        v_rem := v_rem - v_step;
        v_lvl := v_lvl + 1;
    end loop;
    return v_lvl;
end;
$$;

-- ============================================================================
-- Anti-Tamper Trigger on Profiles
-- Protects short_id, banned, ban_reason, xp, level, coins, owned_skins, vault
-- ============================================================================
create or replace function public.prevent_non_admin_profile_tamper()
returns trigger
language plpgsql
as $$
begin
    if current_setting('app.trusted_rpc', true) is distinct from '1' then
        if new.banned is distinct from old.banned
           or new.ban_reason is distinct from old.ban_reason then
            raise exception 'Forbidden: ban status can only be modified by administrator';
        end if;
        if new.short_id is distinct from old.short_id then
            raise exception 'Forbidden: short_id cannot be modified directly';
        end if;
        if new.last_device_hash is distinct from old.last_device_hash
           or new.encrypted_vault is distinct from old.encrypted_vault then
            raise exception 'Forbidden: encrypted security fields must be updated via server RPCs';
        end if;
        if new.xp is distinct from old.xp
           or new.level is distinct from old.level
           or new.coins is distinct from old.coins
           or new.owned_skins is distinct from old.owned_skins then
            raise exception 'Forbidden: progression and inventory must be updated via server RPCs';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_prevent_non_admin_ban_tamper on public.profiles;
drop trigger if exists trg_prevent_non_admin_profile_tamper on public.profiles;
create trigger trg_prevent_non_admin_profile_tamper
    before update on public.profiles
    for each row
    execute function public.prevent_non_admin_profile_tamper();

-- Enforce server-side HMAC device token + ban check BEFORE inserting any profile
create or replace function public.enforce_device_ban_on_profile_insert()
returns trigger
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_meta_hash text;
    v_raw_hash text;
    v_token text;
    v_reason text;
    v_banned_acc public.profiles%rowtype;
begin
    v_raw_hash := trim(coalesce(new.last_device_hash, ''));
    if v_raw_hash = '' then
        select trim(coalesce(raw_user_meta_data ->> 'device_hash', ''))
        into v_meta_hash
        from auth.users
        where id = new.user_id;
        v_raw_hash := coalesce(v_meta_hash, '');
    end if;

    v_token := private_security.normalize_device_token(v_raw_hash);
    new.last_device_hash := v_token;
    if v_token <> '' and trim(coalesce(new.encrypted_vault, '')) = '' then
        new.encrypted_vault := private_security.seal_text(
            jsonb_build_object('device_token', v_token, 'created_at', now())::text
        );
    end if;

    if new.short_id <> 'developer' and v_token <> '' then
        select coalesce(nullif(private_security.unseal_text(reason_enc), ''), nullif(reason, ''), 'Устройство заблокировано администратором.')
        into v_reason
        from public.banned_devices
        where device_hash = v_token
        limit 1;
        if found then
            raise exception 'DEVICE_BANNED: %', v_reason;
        end if;

        select * into v_banned_acc
        from public.profiles
        where banned = true
          and short_id <> 'developer'
          and last_device_hash = v_token
        limit 1;
        if found then
            v_reason := coalesce(nullif(v_banned_acc.ban_reason, ''), 'Устройство связано с заблокированным аккаунтом.');
            insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
            values (v_token, left(v_reason, 160), private_security.seal_text(v_reason), v_banned_acc.user_id, v_banned_acc.short_id, now())
            on conflict (device_hash) do nothing;
            raise exception 'DEVICE_BANNED: %', v_reason;
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists trg_enforce_device_ban_on_profile_insert on public.profiles;
create trigger trg_enforce_device_ban_on_profile_insert
    before insert on public.profiles
    for each row
    execute function public.enforce_device_ban_on_profile_insert();

-- Migrate legacy admin short_id '932760' -> 'developer', upgrade raw hashes -> HMAC tokens, scrub auth metadata & encrypt vaults
do $$
declare
    v_dev_rec record;
    v_prof_rec record;
    v_norm text;
begin
    perform set_config('app.trusted_rpc', '1', true);

    if exists (select 1 from public.profiles where short_id = '932760')
       and not exists (select 1 from public.profiles where short_id = 'developer') then
        update public.profiles
        set short_id = 'developer', updated_at = now()
        where short_id = '932760';

        update public.lobby_members
        set short_id = 'developer'
        where short_id = '932760';
    end if;

    -- Recover any raw legacy device hashes from auth metadata first, then scrub them from auth.users
    update public.profiles p
    set last_device_hash = private_security.normalize_device_token(u.raw_user_meta_data ->> 'device_hash')
    from auth.users u
    where u.id = p.user_id
      and trim(coalesce(p.last_device_hash, '')) = ''
      and trim(coalesce(u.raw_user_meta_data ->> 'device_hash', '')) <> '';

    update auth.users
    set raw_user_meta_data = raw_user_meta_data - 'device_hash'
    where raw_user_meta_data ? 'device_hash';

    -- Convert all plaintext/unkeyed profiles.last_device_hash to server-HMAC tokens + encrypted_vault
    for v_prof_rec in
        select user_id, last_device_hash, encrypted_vault
        from public.profiles
        where trim(coalesce(last_device_hash, '')) <> ''
           or trim(coalesce(encrypted_vault, '')) <> ''
    loop
        v_norm := private_security.normalize_device_token(v_prof_rec.last_device_hash);
        update public.profiles
        set last_device_hash = v_norm,
            encrypted_vault = case
                when trim(coalesce(encrypted_vault, '')) = '' and v_norm <> '' then
                    private_security.seal_text(jsonb_build_object('device_token', v_norm, 'migrated_at', now())::text)
                else encrypted_vault
            end
        where user_id = v_prof_rec.user_id;
    end loop;

    -- Convert all legacy banned_devices rows to server-HMAC tokens + AES-256 reason_enc
    for v_dev_rec in
        select device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at
        from public.banned_devices
    loop
        v_norm := private_security.normalize_device_token(v_dev_rec.device_hash);
        if v_norm <> '' and v_norm <> v_dev_rec.device_hash then
            insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
            values (
                v_norm,
                v_dev_rec.reason,
                private_security.seal_text(coalesce(nullif(v_dev_rec.reason, ''), 'Устройство заблокировано администратором.')),
                v_dev_rec.banned_uid,
                v_dev_rec.banned_short_id,
                v_dev_rec.banned_at
            )
            on conflict (device_hash) do update
            set reason_enc = coalesce(nullif(public.banned_devices.reason_enc, ''), excluded.reason_enc);

            delete from public.banned_devices where device_hash = v_dev_rec.device_hash;
        elsif trim(coalesce(v_dev_rec.reason_enc, '')) = '' then
            update public.banned_devices
            set reason_enc = private_security.seal_text(coalesce(nullif(v_dev_rec.reason, ''), 'Устройство заблокировано администратором.'))
            where device_hash = v_dev_rec.device_hash;
        end if;
    end loop;

    -- Automatically ban devices of all existing banned accounts
    insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
    select distinct on (p.last_device_hash)
        p.last_device_hash,
        left(coalesce(nullif(trim(p.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован'), 160),
        private_security.seal_text(coalesce(nullif(trim(p.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован')),
        p.user_id,
        p.short_id,
        coalesce(p.updated_at, now())
    from public.profiles p
    where p.banned = true
      and p.short_id <> 'developer'
      and trim(coalesce(p.last_device_hash, '')) <> ''
      and char_length(trim(p.last_device_hash)) between 16 and 128
    on conflict (device_hash) do nothing;
end $$;

-- ============================================================================
-- Atomic Server-Verified RPCs for Friends, Presence, Lobbies, Progression & Skins
-- ============================================================================
drop function if exists public.accept_friend_request(uuid);
create or replace function public.accept_friend_request(p_request_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_req public.friend_requests%rowtype;
begin
    select * into v_req
    from public.friend_requests
    where id = p_request_id
      and to_uid = auth.uid()
      and status = 'pending';

    if not found then
        raise exception 'Friend request not found or already handled';
    end if;

    update public.friend_requests
    set status = 'accepted'
    where id = p_request_id;

    insert into public.friendships (user_id, friend_uid)
    values (v_req.from_uid, v_req.to_uid)
    on conflict do nothing;

    insert into public.friendships (user_id, friend_uid)
    values (v_req.to_uid, v_req.from_uid)
    on conflict do nothing;
end;
$$;

drop function if exists public.remove_friendship(uuid);
create or replace function public.remove_friendship(p_friend_uid uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.friendships
    where (user_id = auth.uid() and friend_uid = p_friend_uid)
       or (user_id = p_friend_uid and friend_uid = auth.uid());
end;
$$;

-- Ultra-lightweight single-RPC lobby liveness & roster check (omits 65KB avatar blobs!)
drop function if exists public.get_lobby_heartbeat(uuid);
create or replace function public.get_lobby_heartbeat(p_lobby_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_lobby public.lobbies%rowtype;
    v_members jsonb;
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;

    -- Opportunistic cleanup of stale abandoned lobbies (>2h waiting / >6h total)
    delete from public.lobbies
    where (status <> 'playing' and updated_at < now() - interval '2 hours')
       or (updated_at < now() - interval '6 hours');

    select * into v_lobby from public.lobbies where id = p_lobby_id;
    if not found then
        return jsonb_build_object('closed', true);
    end if;

    select coalesce(jsonb_agg(
        jsonb_build_object(
            'user_id', m.user_id,
            'short_id', m.short_id,
            'display_name', m.display_name,
            'level', m.level,
            'team', m.team,
            'kills', m.kills,
            'deaths', m.deaths,
            'dead', m.dead
        )
        order by lower(m.display_name)
    ), '[]'::jsonb)
    into v_members
    from public.lobby_members m
    where m.lobby_id = p_lobby_id;

    return jsonb_build_object(
        'closed', false,
        'lobby', jsonb_build_object(
            'id', v_lobby.id,
            'host_uid', v_lobby.host_uid,
            'host_name', v_lobby.host_name,
            'map_id', v_lobby.map_id,
            'mode', v_lobby.mode,
            'rounds_to_win', v_lobby.rounds_to_win,
            'allow_bots', v_lobby.allow_bots,
            'status', v_lobby.status
        ),
        'members', v_members
    );
end;
$$;

drop function if exists public.check_client_access(integer, text);
drop function if exists public.check_client_access(integer, text, text);
create or replace function public.check_client_access(
    p_version_code integer,
    p_device_hash text default '',
    p_version_name text default ''
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_cfg public.app_config%rowtype;
    v_token text := private_security.normalize_device_token(p_device_hash);
    v_ban public.banned_devices%rowtype;
    v_my_prof public.profiles%rowtype;
    v_banned_acc public.profiles%rowtype;
    v_effective_token text := '';
    v_update_required boolean := false;
    v_device_banned boolean := false;
    v_ban_reason text := '';
    v_is_dev boolean := false;
    v_ver_name_clean text := lower(trim(coalesce(p_version_name, '')));
begin
    select * into v_cfg from public.app_config where id = 1;
    if not found then
        v_cfg.min_version_code := 10;
        v_cfg.latest_version_name := '0.10beta';
        v_cfg.update_message := 'Доступна только последняя версия Vanguard RTX v0.10beta. Обновите игру, чтобы войти или зарегистрироваться.';
    end if;

    -- Strictly allow ONLY the exact latest build version (blocks older, custom, or mismatched versions!)
    v_update_required := (coalesce(p_version_code, -1) <> v_cfg.min_version_code)
        or (v_ver_name_clean <> lower(trim(v_cfg.latest_version_name)));

    if auth.uid() is not null then
        select * into v_my_prof from public.profiles where user_id = auth.uid();
        if found and v_my_prof.short_id = 'developer' then
            v_is_dev := true;
        end if;
    end if;

    if not v_is_dev then
        -- 1. If the currently signed-in account is banned, automatically ban its current device!
        if v_my_prof.user_id is not null and v_my_prof.banned = true then
            v_device_banned := true;
            v_ban_reason := coalesce(nullif(trim(v_my_prof.ban_reason), ''), 'Аккаунт и устройство заблокированы администратором.');
            v_effective_token := coalesce(nullif(v_token, ''), nullif(trim(coalesce(v_my_prof.last_device_hash, '')), ''));
            if v_effective_token <> '' and char_length(v_effective_token) between 16 and 128 then
                insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
                values (v_effective_token, left(v_ban_reason, 160), private_security.seal_text(v_ban_reason), v_my_prof.user_id, v_my_prof.short_id, now())
                on conflict (device_hash) do update
                set reason = excluded.reason,
                    reason_enc = excluded.reason_enc,
                    banned_uid = excluded.banned_uid,
                    banned_short_id = excluded.banned_short_id;

                if coalesce(v_my_prof.last_device_hash, '') <> v_effective_token then
                    perform set_config('app.trusted_rpc', '1', true);
                    update public.profiles
                    set last_device_hash = v_effective_token,
                        encrypted_vault = private_security.seal_text(jsonb_build_object('device_token', v_effective_token, 'updated_at', now())::text),
                        online = false,
                        updated_at = now()
                    where user_id = v_my_prof.user_id;
                end if;
            end if;
        end if;

        -- 2. Check banned_devices by server-HMAC hardware token
        if not v_device_banned and v_token <> '' then
            select * into v_ban
            from public.banned_devices
            where device_hash = v_token
            limit 1;
            if found then
                v_device_banned := true;
                v_ban_reason := coalesce(nullif(private_security.unseal_text(v_ban.reason_enc), ''), nullif(v_ban.reason, ''), 'Устройство заблокировано администратором.');
            else
                -- 3. Auto-catch any banned account previously seen on this device token
                select * into v_banned_acc
                from public.profiles
                where banned = true
                  and short_id <> 'developer'
                  and last_device_hash = v_token
                limit 1;
                if found then
                    v_device_banned := true;
                    v_ban_reason := coalesce(nullif(trim(v_banned_acc.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован.');
                    insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
                    values (v_token, left(v_ban_reason, 160), private_security.seal_text(v_ban_reason), v_banned_acc.user_id, v_banned_acc.short_id, now())
                    on conflict (device_hash) do nothing;
                end if;
            end if;
        end if;

        -- 4. Persist keyed-HMAC device token & AES-256 encrypted vault on healthy profiles
        if not v_device_banned and v_my_prof.user_id is not null and v_token <> ''
           and (coalesce(v_my_prof.last_device_hash, '') <> v_token or trim(coalesce(v_my_prof.encrypted_vault, '')) = '') then
            perform set_config('app.trusted_rpc', '1', true);
            update public.profiles
            set last_device_hash = v_token,
                encrypted_vault = private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text)
            where user_id = v_my_prof.user_id;
        end if;
    end if;

    return jsonb_build_object(
        'allowed', (not v_update_required and not v_device_banned),
        'update_required', v_update_required,
        'min_version_code', v_cfg.min_version_code,
        'latest_version_name', v_cfg.latest_version_name,
        'update_message', v_cfg.update_message,
        'device_banned', v_device_banned,
        'ban_reason', v_ban_reason
    );
end;
$$;

drop function if exists public.ensure_my_profile(text);
drop function if exists public.ensure_my_profile(text, text);
drop function if exists public.ensure_my_profile(text, text, integer, text);
create or replace function public.ensure_my_profile(
    p_display_name text default null,
    p_device_hash text default '',
    p_version_code integer default 0,
    p_version_name text default ''
)
returns public.profiles
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_uid uuid := auth.uid();
    v_cfg public.app_config%rowtype;
    v_existing public.profiles%rowtype;
    v_banned_acc public.profiles%rowtype;
    v_ban_row public.banned_devices%rowtype;
    v_name text;
    v_short_id text;
    v_token text := private_security.normalize_device_token(p_device_hash);
    v_meta_hash text;
    v_ban_reason text;
    v_ver_name_clean text := lower(trim(coalesce(p_version_name, '')));
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    -- Strictly enforce exact latest version on server during login/signup profile initialization
    select * into v_cfg from public.app_config where id = 1;
    if found then
        if coalesce(p_version_code, -1) <> v_cfg.min_version_code
           or v_ver_name_clean <> lower(trim(v_cfg.latest_version_name)) then
            raise exception 'VERSION_MISMATCH: Требуется последняя версия v% (сборка %)', v_cfg.latest_version_name, v_cfg.min_version_code;
        end if;
    end if;

    if v_token = '' then
        select trim(coalesce(raw_user_meta_data ->> 'device_hash', ''))
        into v_meta_hash
        from auth.users
        where id = v_uid;
        if coalesce(v_meta_hash, '') <> '' then
            v_token := private_security.normalize_device_token(v_meta_hash);
        end if;
    end if;

    -- Scrub any legacy device_hash from GoTrue user_metadata so nothing sensitive lingers in JWT claims
    update auth.users
    set raw_user_meta_data = raw_user_meta_data - 'device_hash'
    where id = v_uid and raw_user_meta_data ? 'device_hash';

    select * into v_existing from public.profiles where user_id = v_uid;
    if found then
        if v_existing.short_id <> 'developer' then
            -- If account is banned, automatically ban its current device token too
            if v_existing.banned = true then
                v_ban_reason := coalesce(nullif(trim(v_existing.ban_reason), ''), 'Аккаунт и устройство заблокированы администратором.');
                if v_token <> '' and char_length(v_token) between 16 and 128 then
                    insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
                    values (v_token, left(v_ban_reason, 160), private_security.seal_text(v_ban_reason), v_existing.user_id, v_existing.short_id, now())
                    on conflict (device_hash) do update
                    set reason = excluded.reason,
                        reason_enc = excluded.reason_enc,
                        banned_uid = excluded.banned_uid,
                        banned_short_id = excluded.banned_short_id;

                    perform set_config('app.trusted_rpc', '1', true);
                    update public.profiles
                    set last_device_hash = v_token,
                        encrypted_vault = private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text),
                        online = false,
                        updated_at = now()
                    where user_id = v_uid;
                end if;
                raise exception 'DEVICE_BANNED: %', v_ban_reason;
            end if;

            if v_token <> '' then
                select * into v_ban_row
                from public.banned_devices
                where device_hash = v_token
                limit 1;
                if found then
                    v_ban_reason := coalesce(nullif(private_security.unseal_text(v_ban_row.reason_enc), ''), nullif(v_ban_row.reason, ''), 'Устройство заблокировано администратором.');
                    raise exception 'DEVICE_BANNED: %', v_ban_reason;
                end if;

                select * into v_banned_acc
                from public.profiles
                where banned = true
                  and short_id <> 'developer'
                  and last_device_hash = v_token
                limit 1;
                if found then
                    v_ban_reason := coalesce(nullif(trim(v_banned_acc.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован.');
                    insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
                    values (v_token, left(v_ban_reason, 160), private_security.seal_text(v_ban_reason), v_banned_acc.user_id, v_banned_acc.short_id, now())
                    on conflict (device_hash) do nothing;
                    raise exception 'DEVICE_BANNED: %', v_ban_reason;
                end if;
            end if;
        end if;

        perform set_config('app.trusted_rpc', '1', true);
        update public.profiles
        set online = true,
            last_seen_at = now(),
            last_device_hash = case when v_token <> '' then v_token else last_device_hash end,
            encrypted_vault = case
                when v_token <> '' then private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text)
                else encrypted_vault
            end,
            updated_at = now()
        where user_id = v_uid
        returning * into v_existing;
        return v_existing;
    end if;

    -- New account signup/profile creation: strictly reject banned devices!
    if v_token <> '' then
        select * into v_ban_row
        from public.banned_devices
        where device_hash = v_token
        limit 1;
        if found then
            v_ban_reason := coalesce(nullif(private_security.unseal_text(v_ban_row.reason_enc), ''), nullif(v_ban_row.reason, ''), 'Устройство заблокировано администратором.');
            raise exception 'DEVICE_BANNED: %', v_ban_reason;
        end if;

        select * into v_banned_acc
        from public.profiles
        where banned = true
          and short_id <> 'developer'
          and last_device_hash = v_token
        limit 1;
        if found then
            v_ban_reason := coalesce(nullif(trim(v_banned_acc.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован.');
            insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
            values (v_token, left(v_ban_reason, 160), private_security.seal_text(v_ban_reason), v_banned_acc.user_id, v_banned_acc.short_id, now())
            on conflict (device_hash) do nothing;
            raise exception 'DEVICE_BANNED: %', v_ban_reason;
        end if;
    end if;

    v_name := trim(coalesce(p_display_name, ''));
    if char_length(v_name) < 3 then
        v_name := 'Player_' || lpad((floor(random() * 900) + 100)::int::text, 3, '0');
    elsif char_length(v_name) > 20 then
        v_name := left(v_name, 20);
    end if;

    for i in 1..12 loop
        v_short_id := (floor(random() * 900000) + 100000)::int::text;
        begin
            insert into public.profiles (
                user_id, short_id, display_name, avatar_data, online, last_seen_at, last_device_hash, encrypted_vault
            )
            values (
                v_uid,
                v_short_id,
                v_name,
                'preset:ghost',
                true,
                now(),
                v_token,
                case when v_token <> '' then private_security.seal_text(jsonb_build_object('device_token', v_token, 'created_at', now())::text) else '' end
            )
            returning * into v_existing;
            return v_existing;
        exception when unique_violation then
            select * into v_existing from public.profiles where user_id = v_uid;
            if found then
                return v_existing;
            end if;
        end;
    end loop;

    raise exception 'Could not allocate unique 6-digit short_id';
end;
$$;

drop function if exists public.ping_presence(boolean);
drop function if exists public.ping_presence(boolean, text);
create or replace function public.ping_presence(
    p_online boolean default true,
    p_device_hash text default ''
)
returns void
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_uid uuid := auth.uid();
    v_token text := private_security.normalize_device_token(p_device_hash);
    v_prof public.profiles%rowtype;
    v_reason text;
begin
    if v_uid is null then
        return;
    end if;
    select * into v_prof from public.profiles where user_id = v_uid;
    if found and v_prof.banned = true and v_prof.short_id <> 'developer' then
        v_reason := coalesce(nullif(trim(v_prof.ban_reason), ''), 'Автобан устройства: аккаунт заблокирован');
        if v_token <> '' and char_length(v_token) between 16 and 128 then
            insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
            values (v_token, left(v_reason, 160), private_security.seal_text(v_reason), v_prof.user_id, v_prof.short_id, now())
            on conflict (device_hash) do nothing;
        end if;
        perform set_config('app.trusted_rpc', '1', true);
        update public.profiles
        set online = false,
            last_device_hash = case when v_token <> '' then v_token else last_device_hash end,
            encrypted_vault = case
                when v_token <> '' then private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text)
                else encrypted_vault
            end,
            updated_at = now()
        where user_id = v_uid;
        return;
    end if;

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set online = coalesce(p_online, false),
        last_seen_at = case when coalesce(p_online, false) then now() else now() - interval '10 minutes' end,
        last_device_hash = case when v_token <> '' then v_token else last_device_hash end,
        encrypted_vault = case
            when v_token <> '' then private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text)
            else encrypted_vault
        end,
        updated_at = now()
    where user_id = v_uid;
end;
$$;

drop function if exists public.award_kill_xp(integer, integer);
create or replace function public.award_kill_xp(
    p_kills integer default 1,
    p_headshots integer default 0
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_prof public.profiles%rowtype;
    v_kills integer := least(greatest(coalesce(p_kills, 1), 1), 5);
    v_hs integer := least(greatest(coalesce(p_headshots, 0), 0), v_kills);
    v_new_xp bigint;
    v_new_level integer;
    v_levels_gained integer;
    v_updated public.profiles%rowtype;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select * into v_prof from public.profiles where user_id = v_uid for update;
    if not found then
        raise exception 'Profile not found';
    end if;

    -- Anti-cheat rate limit: prevent scripts/exploits spamming award_kill_xp faster than once every 1.5 seconds
    if v_prof.last_seen_at > now() - interval '1.5 seconds' and v_prof.short_id <> 'developer' then
        return v_prof;
    end if;

    v_new_xp := v_prof.xp + (v_kills * 100)::bigint;
    v_new_level := public.calc_level_from_xp(v_new_xp);
    v_levels_gained := greatest(v_new_level - v_prof.level, 0);

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set xp = v_new_xp,
        level = v_new_level,
        coins = v_prof.coins + (v_levels_gained * 50),
        total_kills = v_prof.total_kills + v_kills,
        total_headshots = v_prof.total_headshots + v_hs,
        last_seen_at = now(),
        updated_at = now()
    where user_id = v_uid
    returning * into v_updated;

    update public.lobby_members
    set level = v_new_level
    where user_id = v_uid;

    return v_updated;
end;
$$;

drop function if exists public.open_skin_case(text, integer);
create or replace function public.open_skin_case(
    p_skin_id text,
    p_duplicate_refund integer default 45
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_prof public.profiles%rowtype;
    v_clean_skin text := trim(coalesce(p_skin_id, ''));
    v_refund integer := least(greatest(coalesce(p_duplicate_refund, 45), 0), 120);
    v_owned jsonb;
    v_already_owned boolean;
    v_next_coins integer;
    v_updated public.profiles%rowtype;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;
    if v_clean_skin = '' or char_length(v_clean_skin) > 64 then
        raise exception 'Invalid skin id';
    end if;

    select * into v_prof from public.profiles where user_id = v_uid for update;
    if not found then
        raise exception 'Profile not found';
    end if;
    if v_prof.coins < 100 then
        raise exception 'Недостаточно монет (нужно 100 монет)';
    end if;

    v_owned := coalesce(v_prof.owned_skins, '[]'::jsonb);
    v_already_owned := (v_owned ? v_clean_skin);

    if v_already_owned then
        v_next_coins := v_prof.coins - 100 + v_refund;
    else
        v_next_coins := v_prof.coins - 100;
        v_owned := v_owned || jsonb_build_array(v_clean_skin);
    end if;

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set coins = v_next_coins,
        owned_skins = v_owned,
        last_seen_at = now(),
        updated_at = now()
    where user_id = v_uid
    returning * into v_updated;

    return v_updated;
end;
$$;

drop function if exists public.equip_weapon_skin(text, text);
create or replace function public.equip_weapon_skin(
    p_weapon text,
    p_skin_id text default ''
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_prof public.profiles%rowtype;
    v_weapon text := upper(trim(coalesce(p_weapon, '')));
    v_skin text := trim(coalesce(p_skin_id, ''));
    v_equipped jsonb;
    v_updated public.profiles%rowtype;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;
    if v_weapon not in ('AK47', 'M4A1_S', 'AWP', 'DESERT_EAGLE', 'GLOCK_18', 'USP_S', 'MP9', 'P90', 'KNIFE') then
        raise exception 'Invalid weapon type';
    end if;

    select * into v_prof from public.profiles where user_id = v_uid for update;
    if not found then
        raise exception 'Profile not found';
    end if;

    v_equipped := coalesce(v_prof.equipped_skins, '{}'::jsonb);
    if v_skin = '' then
        v_equipped := v_equipped - v_weapon;
    else
        if not (coalesce(v_prof.owned_skins, '[]'::jsonb) ? v_skin) then
            raise exception 'Этот скин не найден в вашем инвентаре';
        end if;
        v_equipped := jsonb_set(v_equipped, array[v_weapon], to_jsonb(v_skin), true);
    end if;

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set equipped_skins = v_equipped,
        last_seen_at = now(),
        updated_at = now()
    where user_id = v_uid
    returning * into v_updated;

    update public.lobby_members
    set equipped_skins = v_equipped
    where user_id = v_uid;

    return v_updated;
end;
$$;

-- ============================================================================
-- Admin Moderation, EXP Booster & Skin Granter (Strictly gated to 'developer')
-- ============================================================================
drop function if exists public.admin_set_ban(text, boolean);
drop function if exists public.admin_set_ban(text, boolean, text);
create or replace function public.admin_set_ban(
    p_target_short_id text,
    p_banned boolean,
    p_reason text default ''
)
returns public.profiles
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_is_admin boolean;
    v_clean_id text;
    v_clean_reason text;
    v_token text;
    v_meta_hash text;
    v_updated public.profiles%rowtype;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    v_clean_id := lower(trim(coalesce(p_target_short_id, '')));
    if v_clean_id = 'developer' then
        raise exception 'Cannot ban the developer account';
    end if;
    if v_clean_id !~ '^[1-9][0-9]{5}$' then
        raise exception 'Invalid 6-digit player ID';
    end if;

    v_clean_reason := left(trim(coalesce(p_reason, '')), 120);
    perform set_config('app.trusted_rpc', '1', true);

    update public.profiles
    set banned = coalesce(p_banned, false),
        ban_reason = case when coalesce(p_banned, false) then v_clean_reason else '' end,
        online = case when coalesce(p_banned, false) then false else online end,
        updated_at = now()
    where short_id = v_clean_id
    returning * into v_updated;

    if not found then
        raise exception 'Player with ID % not found', v_clean_id;
    end if;

    if coalesce(p_banned, false) then
        delete from public.lobby_members where user_id = v_updated.user_id;

        -- Automatically derive & ban the account's HMAC device token!
        v_token := private_security.normalize_device_token(v_updated.last_device_hash);
        if v_token = '' then
            select trim(coalesce(raw_user_meta_data ->> 'device_hash', ''))
            into v_meta_hash
            from auth.users
            where id = v_updated.user_id;
            v_token := private_security.normalize_device_token(v_meta_hash);
        end if;
        if v_token <> '' and char_length(v_token) between 16 and 128 then
            update public.profiles
            set last_device_hash = v_token,
                encrypted_vault = private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text)
            where user_id = v_updated.user_id
            returning * into v_updated;

            insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
            values (
                v_token,
                left(coalesce(nullif(v_clean_reason, ''), 'Автобан устройства: аккаунт заблокирован'), 160),
                private_security.seal_text(coalesce(nullif(v_clean_reason, ''), 'Автобан устройства: аккаунт заблокирован')),
                v_updated.user_id,
                v_updated.short_id,
                now()
            )
            on conflict (device_hash) do update
            set reason = excluded.reason,
                reason_enc = excluded.reason_enc,
                banned_uid = excluded.banned_uid,
                banned_short_id = excluded.banned_short_id,
                banned_at = now();
        end if;
    end if;

    return v_updated;
end;
$$;

drop function if exists public.admin_list_banned();
create or replace function public.admin_list_banned()
returns setof public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    return query
    select * from public.profiles
    where banned = true
    order by updated_at desc
    limit 100;
end;
$$;

drop function if exists public.admin_set_device_ban(text, boolean);
drop function if exists public.admin_set_device_ban(text, boolean, text);
create or replace function public.admin_set_device_ban(
    p_target_short_id text,
    p_banned boolean,
    p_reason text default ''
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_is_admin boolean;
    v_clean_id text;
    v_prof public.profiles%rowtype;
    v_reason text;
    v_token text;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    v_clean_id := lower(trim(coalesce(p_target_short_id, '')));
    if v_clean_id = 'developer' then
        raise exception 'Cannot device-ban developer account';
    end if;
    if v_clean_id !~ '^[1-9][0-9]{5}$' then
        raise exception 'Invalid 6-digit player ID';
    end if;

    select * into v_prof from public.profiles where short_id = v_clean_id for update;
    if not found then
        raise exception 'Player with ID % not found', v_clean_id;
    end if;

    v_token := private_security.normalize_device_token(v_prof.last_device_hash);
    if v_token = '' then
        raise exception 'У игрока ещё не зафиксирован ID устройства (не входил с новой версии).';
    end if;

    v_reason := left(trim(coalesce(p_reason, '')), 160);
    if v_reason = '' then
        v_reason := coalesce(nullif(v_prof.ban_reason, ''), 'Бан устройства администратором');
    end if;

    if p_banned then
        insert into public.banned_devices (device_hash, reason, reason_enc, banned_uid, banned_short_id, banned_at)
        values (v_token, v_reason, private_security.seal_text(v_reason), v_prof.user_id, v_prof.short_id, now())
        on conflict (device_hash) do update
        set reason = excluded.reason,
            reason_enc = excluded.reason_enc,
            banned_uid = excluded.banned_uid,
            banned_short_id = excluded.banned_short_id,
            banned_at = now();

        perform set_config('app.trusted_rpc', '1', true);
        update public.profiles
        set banned = true,
            ban_reason = left(v_reason, 120),
            last_device_hash = v_token,
            encrypted_vault = private_security.seal_text(jsonb_build_object('device_token', v_token, 'updated_at', now())::text),
            online = false,
            updated_at = now()
        where user_id = v_prof.user_id;
    else
        delete from public.banned_devices where device_hash = v_token;
    end if;

    return jsonb_build_object(
        'short_id', v_prof.short_id,
        'display_name', v_prof.display_name,
        'device_hash', v_token,
        'device_banned', p_banned
    );
end;
$$;

drop function if exists public.admin_unban_device_hash(text);
create or replace function public.admin_unban_device_hash(p_device_hash text)
returns void
language plpgsql
security definer
set search_path = public, extensions, private_security
as $$
declare
    v_is_admin boolean;
    v_token text := private_security.normalize_device_token(p_device_hash);
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    delete from public.banned_devices
    where device_hash = v_token
       or device_hash = lower(trim(coalesce(p_device_hash, '')));
end;
$$;

drop function if exists public.admin_list_banned_devices();
create or replace function public.admin_list_banned_devices()
returns setof public.banned_devices
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    return query
    select * from public.banned_devices
    order by banned_at desc
    limit 100;
end;
$$;

drop function if exists public.admin_kick_member(uuid, uuid);
create or replace function public.admin_kick_member(
    p_lobby_id uuid,
    p_target_uid uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    if p_target_uid = auth.uid() then
        raise exception 'Cannot kick yourself';
    end if;

    delete from public.lobby_members
    where lobby_id = p_lobby_id and user_id = p_target_uid;
end;
$$;

drop function if exists public.admin_grant_xp(text, integer);
create or replace function public.admin_grant_xp(
    p_target_short_id text,
    p_xp_amount integer
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
    v_clean_id text;
    v_add_xp bigint;
    v_prof public.profiles%rowtype;
    v_new_xp bigint;
    v_new_level integer;
    v_levels_gained integer;
    v_updated public.profiles%rowtype;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    v_clean_id := lower(trim(coalesce(p_target_short_id, '')));
    if v_clean_id <> 'developer' and v_clean_id !~ '^[1-9][0-9]{5}$' then
        raise exception 'Invalid player ID';
    end if;

    v_add_xp := least(greatest(coalesce(p_xp_amount, 0), 1), 500000)::bigint;

    select * into v_prof from public.profiles where short_id = v_clean_id for update;
    if not found then
        raise exception 'Player with ID % not found', v_clean_id;
    end if;

    v_new_xp := v_prof.xp + v_add_xp;
    v_new_level := public.calc_level_from_xp(v_new_xp);
    v_levels_gained := greatest(v_new_level - v_prof.level, 0);

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set xp = v_new_xp,
        level = v_new_level,
        coins = v_prof.coins + (v_levels_gained * 50),
        updated_at = now()
    where user_id = v_prof.user_id
    returning * into v_updated;

    update public.lobby_members
    set level = v_new_level
    where user_id = v_prof.user_id;

    return v_updated;
end;
$$;

drop function if exists public.admin_grant_skins(text, text[]);
create or replace function public.admin_grant_skins(
    p_target_short_id text,
    p_skin_ids text[]
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
    v_clean_id text;
    v_prof public.profiles%rowtype;
    v_owned jsonb;
    v_skin text;
    v_updated public.profiles%rowtype;
begin
    select exists(
        select 1 from public.profiles
        where user_id = auth.uid() and short_id = 'developer'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    v_clean_id := lower(trim(coalesce(p_target_short_id, '')));
    if v_clean_id <> 'developer' and v_clean_id !~ '^[1-9][0-9]{5}$' then
        raise exception 'Invalid player ID';
    end if;

    select * into v_prof from public.profiles where short_id = v_clean_id for update;
    if not found then
        raise exception 'Player with ID % not found', v_clean_id;
    end if;

    v_owned := coalesce(v_prof.owned_skins, '[]'::jsonb);
    foreach v_skin in array coalesce(p_skin_ids, array[]::text[]) loop
        v_skin := trim(coalesce(v_skin, ''));
        if v_skin <> '' and char_length(v_skin) <= 64 and not (v_owned ? v_skin) then
            v_owned := v_owned || jsonb_build_array(v_skin);
        end if;
    end loop;

    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set owned_skins = v_owned,
        updated_at = now()
    where user_id = v_prof.user_id
    returning * into v_updated;

    return v_updated;
end;
$$;

grant usage on schema public to anon, authenticated;
grant select on table public.app_config to anon, authenticated;
grant select, insert, update, delete on table
    public.profiles,
    public.friend_requests,
    public.friendships,
    public.lobbies,
    public.lobby_members,
    public.lobby_invites
to authenticated;

grant execute on function public.check_client_access(integer, text, text) to anon, authenticated;
grant execute on function public.calc_level_step_xp(integer) to authenticated;
grant execute on function public.calc_level_from_xp(bigint) to authenticated;
grant execute on function public.ensure_my_profile(text, text, integer, text) to authenticated;
grant execute on function public.get_lobby_heartbeat(uuid) to authenticated;
grant execute on function public.accept_friend_request(uuid) to authenticated;
grant execute on function public.remove_friendship(uuid) to authenticated;
grant execute on function public.ping_presence(boolean, text) to authenticated;
grant execute on function public.award_kill_xp(integer, integer) to authenticated;
grant execute on function public.open_skin_case(text, integer) to authenticated;
grant execute on function public.equip_weapon_skin(text, text) to authenticated;
grant execute on function public.admin_set_ban(text, boolean, text) to authenticated;
grant execute on function public.admin_list_banned() to authenticated;
grant execute on function public.admin_set_device_ban(text, boolean, text) to authenticated;
grant execute on function public.admin_unban_device_hash(text) to authenticated;
grant execute on function public.admin_list_banned_devices() to authenticated;
grant execute on function public.admin_kick_member(uuid, uuid) to authenticated;
grant execute on function public.admin_grant_xp(text, integer) to authenticated;
grant execute on function public.admin_grant_skins(text, text[]) to authenticated;

notify pgrst, 'reload schema';
