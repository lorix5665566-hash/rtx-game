-- ============================================================================
-- Vanguard RTX v0.07beta — Supabase Cloud Multiplayer, Progression & Skins Schema
-- Paste this entire script into Supabase Dashboard -> SQL Editor -> Run.
-- Also set: Authentication -> Providers -> Email -> Confirm email = OFF
-- ============================================================================

create extension if not exists pgcrypto;

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
-- Protects short_id, banned, ban_reason, xp, level, coins, owned_skins
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

-- Migrate legacy admin short_id '932760' -> 'developer' safely
do $$
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
end $$;

-- ============================================================================
-- Atomic Server-Verified RPCs for Friends, Presence, Progression & Skins
-- ============================================================================
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

create or replace function public.ensure_my_profile(p_display_name text default null)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
    v_existing public.profiles%rowtype;
    v_name text;
    v_short_id text;
begin
    if v_uid is null then
        raise exception 'Not authenticated';
    end if;

    select * into v_existing from public.profiles where user_id = v_uid;
    if found then
        perform set_config('app.trusted_rpc', '1', true);
        update public.profiles
        set online = true, last_seen_at = now(), updated_at = now()
        where user_id = v_uid
        returning * into v_existing;
        return v_existing;
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
            insert into public.profiles (user_id, short_id, display_name, avatar_data, online, last_seen_at)
            values (v_uid, v_short_id, v_name, 'preset:ghost', true, now())
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

create or replace function public.ping_presence(p_online boolean default true)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        return;
    end if;
    perform set_config('app.trusted_rpc', '1', true);
    update public.profiles
    set online = coalesce(p_online, false),
        last_seen_at = case when coalesce(p_online, false) then now() else now() - interval '10 minutes' end,
        updated_at = now()
    where user_id = v_uid;
end;
$$;

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
create or replace function public.admin_set_ban(
    p_target_short_id text,
    p_banned boolean,
    p_reason text default ''
)
returns public.profiles
language plpgsql
security definer
set search_path = public
as $$
declare
    v_is_admin boolean;
    v_clean_id text;
    v_clean_reason text;
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
    end if;

    return v_updated;
end;
$$;

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
grant select, insert, update, delete on table
    public.profiles,
    public.friend_requests,
    public.friendships,
    public.lobbies,
    public.lobby_members,
    public.lobby_invites
to authenticated;

grant execute on function public.calc_level_step_xp(integer) to authenticated;
grant execute on function public.calc_level_from_xp(bigint) to authenticated;
grant execute on function public.ensure_my_profile(text) to authenticated;
grant execute on function public.accept_friend_request(uuid) to authenticated;
grant execute on function public.remove_friendship(uuid) to authenticated;
grant execute on function public.ping_presence(boolean) to authenticated;
grant execute on function public.award_kill_xp(integer, integer) to authenticated;
grant execute on function public.open_skin_case(text, integer) to authenticated;
grant execute on function public.equip_weapon_skin(text, text) to authenticated;
grant execute on function public.admin_set_ban(text, boolean, text) to authenticated;
grant execute on function public.admin_list_banned() to authenticated;
grant execute on function public.admin_kick_member(uuid, uuid) to authenticated;
grant execute on function public.admin_grant_xp(text, integer) to authenticated;
grant execute on function public.admin_grant_skins(text, text[]) to authenticated;

notify pgrst, 'reload schema';
