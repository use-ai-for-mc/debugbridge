// Bridge protocol types
export interface BridgeRequest {
  id: string;
  type: 'execute' | 'search' | 'snapshot' | 'screenshot' | 'runCommand' | 'status';
  payload: Record<string, unknown>;
}

export interface BridgeResponse {
  id: string;
  success: boolean;
  result?: unknown;
  output?: string;
  error?: string;
}

export interface SessionInfo {
  version: string;
  mappingStatus: 'mojang' | 'passthrough';
  obfuscated: boolean;
  refs: number;
  gameDir?: string;
  logsDir?: string;
  latestLog?: string;
  latestLogExists?: boolean;
  debugLog?: string;
  debugLogExists?: boolean;
}

// Game state types
export interface GameSnapshot {
  player?: {
    position: { x: number; y: number; z: number };
    health: number;
    maxHealth: number;
    food: number;
    saturation: number;
    experienceLevel: number;
    experienceProgress: number;
    gameMode: string;
    dimension: string;
  };
  world?: {
    time: number;
    dayTime: number;
    weather: string;
    difficulty: string;
  };
  performance?: {
    fps: number;
  };
}

// Object inspection types
export interface JavaObjectRef {
  refId: string;
  className: string;
  displayValue?: string;
  isNull: boolean;
}

export interface InspectedObject {
  refId: string;
  className: string;
  fields: InspectedField[];
  methods?: string[];
  superClasses?: string[];
  interfaces?: string[];
}

export interface InspectedField {
  name: string;
  type: string;
  value: InspectedValue;
  modifiers?: string[];
}

export type InspectedValue =
  | { kind: 'primitive'; type: string; value: string | number | boolean | null }
  | { kind: 'string'; value: string }
  | { kind: 'object'; ref: JavaObjectRef }
  | { kind: 'array'; length: number; elementType: string; ref: JavaObjectRef }
  | { kind: 'null' };

// Console types
export interface ConsoleEntry {
  id: string;
  timestamp: Date;
  type: 'input' | 'output' | 'error' | 'result';
  content: string;
  code?: string;
  duration?: number;
}

// Connection status
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

// Pinned objects
export interface PinnedObject {
  id: string;
  name: string;
  refId: string;
  className: string;
  code: string; // Groovy code to retrieve it
  pinnedAt: Date;
}
