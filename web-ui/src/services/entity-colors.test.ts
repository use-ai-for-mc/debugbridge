import { describe, expect, it } from 'vitest';

import { ENTITY_COLORS, entityColor } from './entity-colors';

describe('entityColor', () => {
  it('maps hostile mobs, including separator variants, to red', () => {
    expect(entityColor('Zombie')).toBe(ENTITY_COLORS.hostile);
    expect(entityColor('net.minecraft.world.entity.monster.CaveSpider')).toBe(ENTITY_COLORS.hostile);
    expect(entityColor('magma_cube')).toBe(ENTITY_COLORS.hostile);
    expect(entityColor('breezing')).toBe(ENTITY_COLORS.hostile);
  });

  it('maps passive and aquatic mobs to their category colors', () => {
    expect(entityColor('iron_golem')).toBe(ENTITY_COLORS.passive);
    expect(entityColor('Villager')).toBe(ENTITY_COLORS.passive);
    expect(entityColor('glow_squid')).toBe(ENTITY_COLORS.water);
    expect(entityColor('tropical_fish')).toBe(ENTITY_COLORS.water);
  });

  it('maps players, items, and projectiles to stable colors', () => {
    expect(entityColor({ type: 'ServerPlayer' })).toBe(ENTITY_COLORS.player);
    expect(entityColor('experience_orb')).toBe(ENTITY_COLORS.item);
    expect(entityColor('shulker_bullet')).toBe(ENTITY_COLORS.projectile);
  });

  it('falls back for unknown or modded entity types', () => {
    expect(entityColor('mod.example.CustomRideVehicle')).toBe(ENTITY_COLORS.unknown);
  });
});
