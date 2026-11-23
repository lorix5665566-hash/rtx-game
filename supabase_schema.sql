-- ============================================================================
-- Vanguard RTX — Supabase Cloud Multiplayer & Profile Schema (Zero-Trust RLS)
-- Paste this entire script into Supabase Dashboard -> SQL Editor -> Run.
-- Also set: Authentication -> Providers -> Email -> Confirm email = OFF
-- ============================================================================

create extension if not exists pgcrypto;

-- 1. Player Profiles (unique 6-digit ID, editable nickname & avatar)
create table if not exists public.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    short_id text not null unique check (short_id ~ '^[1-9][0-9]{5}$'),
    display_name text not null check (char_length(display_name) between 3 and 20),
    avatar_data text not null default 'preset:ghost' check (char_length(avatar_data) <= 65000),
    online boolean not null default true,
    total_kills integer not null default 0 check (total_kills >= 0),
    total_headshots integer not null default 0 check (total_headshots >= 0),
    banned boolean not null default false,
    ban_reason text not null default '' check (char_length(ban_reason) <= 120),
    updated_at timestamptz not null default now()
);

-- Idempotent migration for existing deployments
alter table public.profiles add column if not exists banned boolean not null default false;
alter table public.profiles add column if not exists ban_reason text not null default '';

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
    for insert to authenticated with check (auth.uid() = user_id);

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

-- Friendships policies (reads scoped to owner; mutations via verified RPCs below)
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
-- Atomic Server-Verified RPCs for Mutual Friendships
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

grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on table
    public.profiles,
    public.friend_requests,
    public.friendships,
    public.lobbies,
    public.lobby_members,
    public.lobby_invites
to authenticated;

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
        update public.profiles
        set online = true, updated_at = now()
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
            insert into public.profiles (user_id, short_id, display_name, avatar_data, online)
            values (v_uid, v_short_id, v_name, 'preset:ghost', true)
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

grant execute on function public.ensure_my_profile(text) to authenticated;
grant execute on function public.accept_friend_request(uuid) to authenticated;
grant execute on function public.remove_friendship(uuid) to authenticated;

-- ============================================================================
-- Admin Moderation & Anti-Tamper Protection (Strictly gated to short_id '932760')
-- ============================================================================
create or replace function public.prevent_non_admin_ban_tamper()
returns trigger
language plpgsql
as $$
begin
    if (new.banned is distinct from old.banned or new.ban_reason is distinct from old.ban_reason)
       and current_setting('app.admin_rpc', true) is distinct from '1' then
        raise exception 'Forbidden: ban status can only be modified by administrator';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_prevent_non_admin_ban_tamper on public.profiles;
create trigger trg_prevent_non_admin_ban_tamper
    before update on public.profiles
    for each row
    execute function public.prevent_non_admin_ban_tamper();

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
        where user_id = auth.uid() and short_id = '932760'
    ) into v_is_admin;

    if not v_is_admin then
        raise exception 'Forbidden: admin privileges required';
    end if;

    v_clean_id := trim(coalesce(p_target_short_id, ''));
    if v_clean_id !~ '^[1-9][0-9]{5}$' then
        raise exception 'Invalid 6-digit player ID';
    end if;
    if v_clean_id = '932760' then
        raise exception 'Cannot ban the administrator account';
    end if;

    v_clean_reason := left(trim(coalesce(p_reason, '')), 120);
    perform set_config('app.admin_rpc', '1', true);

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
        where user_id = auth.uid() and short_id = '932760'
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
        where user_id = auth.uid() and short_id = '932760'
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

grant execute on function public.admin_set_ban(text, boolean, text) to authenticated;
grant execute on function public.admin_list_banned() to authenticated;
grant execute on function public.admin_kick_member(uuid, uuid) to authenticated;

notify pgrst, 'reload schema';
