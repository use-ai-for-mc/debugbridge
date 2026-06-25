import { defineStore } from 'pinia';
import { ref, computed, onScopeDispose } from 'vue';
import { bridge } from '../services/bridge';

export interface PrimaryEquipment {
  slot: string;     // e.g. "HEAD"
  itemId: string;   // registry key e.g. "minecraft:iron_helmet"
}

export interface NearbyEntity {
  id: number;
  type: string;         // short name e.g. "Zombie"
  fullType: string;     // full class e.g. "net.minecraft.world.entity.monster.Zombie"
  distance: number;
  x: number;
  y: number;
  z: number;
  customName: string | null;
  primaryEquipment: PrimaryEquipment | null;
  status: 'stable' | 'new' | 'despawned';
  lastSeen: number;
}

export interface DisplayItem {
  itemId: string;
  count: number;
  name?: string;
}

export interface EquipmentItem {
  itemId: string;
  damage?: number;
  maxDamage?: number;
  name?: string;
}

export interface FrameItem {
  itemId: string;
  count: number;
  damage?: number;
  maxDamage?: number;
  name?: string;
}

export interface EntityDetails {
  entityId: number;
  customName: string | null;
  health: number | null;
  maxHealth: number | null;
  armor: number | null;
  equipment: Record<string, EquipmentItem>;
  tags: string[];
  isOnFire: boolean;
  isSprinting: boolean;
  vehicle: string | null;
  passengers: string[];
  displayText: string | null;
  displayItem: DisplayItem | null;
  displayBlock: string | null;
  frameItem: FrameItem | null;
  raw: Record<string, unknown>;
}

const DESPAWN_LINGER_MS = 1500;
const NEW_STATUS_MS = 2000;
const MAX_ENTITIES = 100;
const GAZE_POLL_MS = 200;
const GAZE_RANGE = 64;

export const useEntitiesStore = defineStore('entities', () => {
  const entities = ref<NearbyEntity[]>([]);
  const selectedEntityId = ref<number | null>(null);
  const selectedDetails = ref<EntityDetails | null>(null);
  const equipmentTextures = ref<Record<string, string>>({});
  const equipmentSpriteNames = ref<Record<string, string>>({});
  const equipmentTextureDegraded = ref<Record<string, boolean>>({});
  // Cache keyed by `${entityId}:${slot}:${itemId}` — rendering the entity's actual
  // ItemStack honors damage-based and CMD-based model overrides.
  const entityPrimaryTextures = ref<Record<string, string>>({});
  const entityPrimaryTextureDegraded = ref<Record<string, boolean>>({});
  const inFlightEntityPrimary = new Map<string, Promise<string | null>>();
  const isLoading = ref(false);
  const isLoadingDetails = ref(false);
  const error = ref<string | null>(null);
  const range = ref<number>(parseInt(localStorage.getItem('debugbridge-entity-range') ?? '10', 10));
  const autoRefreshEnabled = ref(false);
  const followGazeEnabled = ref(false);
  const sortBy = ref<'distance' | 'type' | 'id'>('distance');
  const viewMode = ref<'list' | 'grid'>(
    (localStorage.getItem('debugbridge-entity-view') as 'list' | 'grid') ?? 'list',
  );

  let refreshTimer: ReturnType<typeof setInterval> | null = null;
  let gazeTimer: ReturnType<typeof setInterval> | null = null;
  let detailsRequestSeq = 0;
  let gazeInFlight = false;
  const newStatusTimers = new Map<number, ReturnType<typeof setTimeout>>();

  const sortedEntities = computed(() => {
    const list = [...entities.value];
    switch (sortBy.value) {
      case 'distance': return list.sort((a, b) => a.distance - b.distance);
      case 'type': return list.sort((a, b) => a.type.localeCompare(b.type) || a.distance - b.distance);
      case 'id': return list.sort((a, b) => a.id - b.id);
      default: return list;
    }
  });

  const aliveCount = computed(() => entities.value.filter(e => e.status !== 'despawned').length);

  function setRange(val: number) {
    range.value = val;
    localStorage.setItem('debugbridge-entity-range', String(val));
  }

  function setViewMode(mode: 'list' | 'grid') {
    viewMode.value = mode;
    localStorage.setItem('debugbridge-entity-view', mode);
  }

  function entityPrimaryKey(entityId: number, slot: string, itemId: string): string {
    return `${entityId}:${slot}:${itemId}`;
  }

  function scheduleNewStatusClear(entityId: number) {
    const existing = newStatusTimers.get(entityId);
    if (existing) clearTimeout(existing);
    const timer = setTimeout(() => {
      newStatusTimers.delete(entityId);
      const entity = entities.value.find(e => e.id === entityId);
      if (entity?.status === 'new') entity.status = 'stable';
    }, NEW_STATUS_MS);
    newStatusTimers.set(entityId, timer);
  }

  function clearNewStatusTimers() {
    for (const timer of newStatusTimers.values()) {
      clearTimeout(timer);
    }
    newStatusTimers.clear();
    for (const entity of entities.value) {
      if (entity.status === 'new') entity.status = 'stable';
    }
  }

  async function fetchEntityPrimaryTextureCached(
    entityId: number,
    slot: string,
    itemId: string,
  ): Promise<string | null> {
    const key = entityPrimaryKey(entityId, slot, itemId);
    const cached = entityPrimaryTextures.value[key];
    if (cached) return cached;
    const inFlight = inFlightEntityPrimary.get(key);
    if (inFlight) return inFlight;

    const promise = (async () => {
      try {
        const tex = await bridge.getEntityItemTexture(entityId, slot);
        const url = `data:image/png;base64,${tex.base64Png}`;
        entityPrimaryTextures.value = { ...entityPrimaryTextures.value, [key]: url };
        entityPrimaryTextureDegraded.value = {
          ...entityPrimaryTextureDegraded.value,
          [key]: (tex.spriteName ?? '').startsWith('fallback:'),
        };
        return url;
      } catch {
        return null;
      } finally {
        inFlightEntityPrimary.delete(key);
      }
    })();
    inFlightEntityPrimary.set(key, promise);
    return promise;
  }

  async function fetchEntities(): Promise<void> {
    if (isLoading.value) return;
    isLoading.value = true;
    error.value = null;

    try {
      const resp = await bridge.getNearbyEntities(range.value, MAX_ENTITIES);
      const newList: NearbyEntity[] = [];
      const now = Date.now();

      for (const raw of resp.entities) {
        const e = raw as Record<string, unknown>;
        const id = Number(e.id);
        if (isNaN(id)) continue;

        const fullType = String(e.type ?? '');
        // Prefer typeId (registry description like "entity.minecraft.villager") for readable name
        const typeId = e.typeId ? String(e.typeId) : '';
        const typeIdParts = typeId.split('.');
        const typeFromRegistry = typeIdParts.length >= 3 ? typeIdParts[typeIdParts.length - 1] : '';
        const typeParts = fullType.split('.');
        const type = typeFromRegistry || typeParts[typeParts.length - 1] || fullType;

        let primaryEquipment: PrimaryEquipment | null = null;
        if (e.primaryEquipment && typeof e.primaryEquipment === 'object') {
          const pe = e.primaryEquipment as Record<string, unknown>;
          if (pe.itemId && pe.slot) {
            primaryEquipment = {
              slot: String(pe.slot),
              itemId: String(pe.itemId),
            };
          }
        }

        newList.push({
          id,
          type,
          fullType,
          distance: Number(e.distance) || 0,
          x: Number(e.x) || 0,
          y: Number(e.y) || 0,
          z: Number(e.z) || 0,
          customName: e.customName ? String(e.customName) : null,
          primaryEquipment,
          status: 'stable',
          lastSeen: now,
        });
      }

      // Spawn/despawn detection
      const oldIds = new Set(entities.value.filter(e => e.status !== 'despawned').map(e => e.id));
      const newIds = new Set(newList.map(e => e.id));

      for (const entity of newList) {
        if (!oldIds.has(entity.id)) {
          entity.status = 'new';
          scheduleNewStatusClear(entity.id);
        }
      }

      // Keep recently-despawned entities visible
      const despawned = entities.value
        .filter(e => !newIds.has(e.id) && e.status !== 'despawned')
        .map(e => ({ ...e, status: 'despawned' as const, lastSeen: now }));

      // Exclude any previously-despawned entity that came back in the new list —
      // otherwise it would appear twice (once alive, once as a lingering ghost).
      const stillLingering = entities.value
        .filter(e => e.status === 'despawned' && !newIds.has(e.id) && (now - e.lastSeen) < DESPAWN_LINGER_MS);

      entities.value = [...newList, ...despawned, ...stillLingering];

      // If selected entity despawned, clear details
      if (selectedEntityId.value !== null && !newIds.has(selectedEntityId.value)) {
        selectedDetails.value = null;
      }
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading.value = false;
    }
  }

  async function fetchEntityDetails(entityId: number): Promise<void> {
    const requestSeq = ++detailsRequestSeq;
    const isSuperseded = () => requestSeq !== detailsRequestSeq;
    const isStale = () => isSuperseded() || selectedEntityId.value !== entityId;
    isLoadingDetails.value = true;

    try {
      const data = await bridge.getEntityDetails(entityId);
      if (isStale()) return;
      if (!data || data.gone) {
        selectedDetails.value = null;
        return;
      }

      const eq: Record<string, EquipmentItem> = {};
      if (data.equipment && typeof data.equipment === 'object') {
        for (const [slot, v] of Object.entries(data.equipment as Record<string, unknown>)) {
          if (typeof v === 'string') {
            // Back-compat: older mod builds sent the descriptionId as a bare string.
            eq[slot] = { itemId: v };
          } else if (v && typeof v === 'object') {
            const o = v as Record<string, unknown>;
            eq[slot] = {
              itemId: String(o.itemId ?? ''),
              damage: o.damage != null ? Number(o.damage) : undefined,
              maxDamage: o.maxDamage != null ? Number(o.maxDamage) : undefined,
              name: o.name ? String(o.name) : undefined,
            };
          }
        }
      }

      // Update the entity's customName from detail fetch
      const entity = entities.value.find(e => e.id === entityId);
      if (entity && data.customName) {
        entity.customName = String(data.customName);
      }

      let displayItem: DisplayItem | null = null;
      if (data.displayItem && typeof data.displayItem === 'object') {
        const di = data.displayItem as Record<string, unknown>;
        displayItem = {
          itemId: String(di.itemId ?? ''),
          count: Number(di.count ?? 1),
          name: di.name ? String(di.name) : undefined,
        };
      }

      let frameItem: FrameItem | null = null;
      if (data.frameItem && typeof data.frameItem === 'object') {
        const fi = data.frameItem as Record<string, unknown>;
        frameItem = {
          itemId: String(fi.itemId ?? ''),
          count: Number(fi.count ?? 1),
          damage: fi.damage != null ? Number(fi.damage) : undefined,
          maxDamage: fi.maxDamage != null ? Number(fi.maxDamage) : undefined,
          name: fi.name ? String(fi.name) : undefined,
        };
      }

      selectedDetails.value = {
        entityId,
        customName: data.customName ? String(data.customName) : null,
        health: data.health != null ? Number(data.health) : null,
        maxHealth: data.maxHealth != null ? Number(data.maxHealth) : null,
        armor: data.armor != null ? Number(data.armor) : null,
        equipment: eq,
        tags: Array.isArray(data.tags) ? (data.tags as unknown[]).map(String) : [],
        isOnFire: Boolean(data.isOnFire),
        isSprinting: Boolean(data.isSprinting),
        vehicle: data.vehicle ? String(data.vehicle) : null,
        passengers: Array.isArray(data.passengers) ? (data.passengers as unknown[]).map(String) : [],
        displayText: data.displayText ? String(data.displayText) : null,
        displayItem,
        displayBlock: data.displayBlock ? String(data.displayBlock) : null,
        frameItem,
        raw: data as Record<string, unknown>,
      };

      // Fetch textures for each equipment slot — plus the FRAME slot if this
      // entity has a framed item. Reuses equipmentTextures / equipmentSpriteNames
      // keyed by slot name so the UI can look up "FRAME" the same way.
      if (isStale()) return;
      equipmentTextures.value = {};
      equipmentSpriteNames.value = {};
      equipmentTextureDegraded.value = {};
      const slots = Object.keys(eq);
      if (frameItem) slots.push('FRAME');
      if (slots.length > 0) {
        await Promise.all(slots.map(async (slot) => {
          try {
            const tex = await bridge.getEntityItemTexture(entityId, slot);
            if (isStale()) return;
            equipmentTextures.value = {
              ...equipmentTextures.value,
              [slot]: `data:image/png;base64,${tex.base64Png}`,
            };
            equipmentTextureDegraded.value = {
              ...equipmentTextureDegraded.value,
              [slot]: (tex.spriteName ?? '').startsWith('fallback:'),
            };
            if (tex.spriteName) {
              equipmentSpriteNames.value = {
                ...equipmentSpriteNames.value,
                [slot]: tex.spriteName,
              };
            }
          } catch {
            // Texture fetch failure is non-fatal; just skip
          }
        }));
      }
    } catch {
      // Silently fail for details
    } finally {
      if (!isSuperseded()) {
        isLoadingDetails.value = false;
      }
    }
  }

  function selectEntity(id: number | null) {
    const previousId = selectedEntityId.value;
    detailsRequestSeq++;
    selectedEntityId.value = id;
    selectedDetails.value = null;
    equipmentTextures.value = {};
    equipmentSpriteNames.value = {};
    equipmentTextureDegraded.value = {};
    isLoadingDetails.value = false;

    if (previousId !== null && previousId !== id) {
      bridge.setEntityGlow(previousId, false).catch(() => {});
    }

    if (id !== null) {
      bridge.setEntityGlow(id, true).catch(() => {});
      fetchEntityDetails(id);
    }
  }

  function startAutoRefresh() {
    if (refreshTimer) return;
    autoRefreshEnabled.value = true;
    fetchEntities();
    refreshTimer = setInterval(() => {
      fetchEntities();
    }, 2000);
  }

  function stopAutoRefresh() {
    autoRefreshEnabled.value = false;
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
    clearNewStatusTimers();
  }

  async function fetchLookedAtEntity(): Promise<void> {
    if (gazeInFlight) return;
    gazeInFlight = true;
    try {
      const id = await bridge.getLookedAtEntity(GAZE_RANGE);
      if (id !== null && id !== selectedEntityId.value) {
        selectEntity(id);
      }
    } catch {
      // Ignore transient errors during polling
    } finally {
      gazeInFlight = false;
    }
  }

  function startFollowGaze() {
    if (gazeTimer) return;
    followGazeEnabled.value = true;
    fetchLookedAtEntity();
    gazeTimer = setInterval(() => fetchLookedAtEntity(), GAZE_POLL_MS);
  }

  function stopFollowGaze() {
    followGazeEnabled.value = false;
    if (gazeTimer) {
      clearInterval(gazeTimer);
      gazeTimer = null;
    }
  }

  function clearEntityPrimaryTextureCache() {
    entityPrimaryTextures.value = {};
    entityPrimaryTextureDegraded.value = {};
  }

  async function refreshEntities(): Promise<void> {
    clearEntityPrimaryTextureCache();
    await fetchEntities();
  }

  onScopeDispose(() => {
    stopAutoRefresh();
    stopFollowGaze();
    clearNewStatusTimers();
    detailsRequestSeq++;
  });

  return {
    entities,
    sortedEntities,
    aliveCount,
    selectedEntityId,
    selectedDetails,
    equipmentTextures,
    equipmentSpriteNames,
    equipmentTextureDegraded,
    entityPrimaryTextures,
    entityPrimaryTextureDegraded,
    isLoading,
    isLoadingDetails,
    error,
    range,
    autoRefreshEnabled,
    followGazeEnabled,
    sortBy,
    viewMode,
    setRange,
    setViewMode,
    fetchEntities,
    refreshEntities,
    fetchEntityDetails,
    fetchEntityPrimaryTextureCached,
    entityPrimaryKey,
    clearEntityPrimaryTextureCache,
    selectEntity,
    startAutoRefresh,
    stopAutoRefresh,
    startFollowGaze,
    stopFollowGaze,
    fetchLookedAtEntity,
  };
});
