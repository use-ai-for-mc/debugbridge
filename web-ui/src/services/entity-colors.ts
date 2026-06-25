type EntityColorInput = string | { type: string };

export const ENTITY_COLORS = {
  hostile: '#ff5555',
  passive: '#55ff55',
  water: '#55ffff',
  player: '#ff55ff',
  item: '#ffaa00',
  projectile: '#aaaaaa',
  unknown: '#888888',
} as const;

const COLOR_RULES: Array<{ color: string; tokens: string[] }> = [
  {
    color: ENTITY_COLORS.projectile,
    tokens: ['arrow', 'fireball', 'snowball', 'trident', 'shulkerbullet', 'thrownegg', 'thrownpotion'],
  },
  {
    color: ENTITY_COLORS.hostile,
    tokens: [
      'zombie',
      'skeleton',
      'creeper',
      'spider',
      'enderman',
      'witch',
      'blaze',
      'ghast',
      'slime',
      'magmacube',
      'phantom',
      'drowned',
      'husk',
      'stray',
      'pillager',
      'vindicator',
      'ravager',
      'vex',
      'evoker',
      'warden',
      'wither',
      'piglin',
      'hoglin',
      'zoglin',
      'piglinbrute',
      'breez',
      'bogged',
      'guardian',
      'elderguardian',
      'shulker',
      'silverfish',
      'endermite',
      'cavespider',
    ],
  },
  {
    color: ENTITY_COLORS.passive,
    tokens: [
      'cow',
      'pig',
      'sheep',
      'chicken',
      'horse',
      'donkey',
      'mule',
      'rabbit',
      'cat',
      'wolf',
      'parrot',
      'fox',
      'bee',
      'turtle',
      'frog',
      'goat',
      'camel',
      'sniffer',
      'armadillo',
      'mooshroom',
      'panda',
      'llama',
      'villager',
      'irongolem',
      'snowgolem',
      'allay',
      'axolotl',
      'strider',
    ],
  },
  {
    color: ENTITY_COLORS.water,
    tokens: ['squid', 'dolphin', 'cod', 'salmon', 'tropicalfish', 'pufferfish', 'glowsquid', 'tadpole'],
  },
  {
    color: ENTITY_COLORS.player,
    tokens: ['player', 'serverplayer'],
  },
  {
    color: ENTITY_COLORS.item,
    tokens: ['item', 'experienceorb'],
  },
];

export function entityColor(input: EntityColorInput): string {
  const rawType = typeof input === 'string' ? input : input.type;
  const normalized = normalizeEntityType(rawType);
  const rule = COLOR_RULES.find(({ tokens }) => tokens.some(token => normalized.includes(token)));
  return rule?.color ?? ENTITY_COLORS.unknown;
}

function normalizeEntityType(type: string): string {
  return type.toLowerCase().replace(/[^a-z0-9]/g, '');
}
