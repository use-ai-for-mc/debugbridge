import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import * as inspect from '../services/groovy-helpers';

export interface BrowserNode {
  id: string;
  name: string;
  path: string;           // Groovy expression to get this value
  type: 'class' | 'instance' | 'field' | 'method' | 'collection-item';
  className?: string;
  shortName?: string;
  value?: unknown;
  displayValue?: string;
  expandable: boolean;
  expanded: boolean;
  loading: boolean;
  children?: BrowserNode[];
  error?: string;
  parent?: BrowserNode;
  // For collection items
  index?: number;
  // For methods
  methodSignature?: string;
}

export interface EntryPoint {
  name: string;
  description: string;
  path: string;
  icon: string;
}

export const ENTRY_POINTS: EntryPoint[] = [
  {
    name: 'Minecraft',
    description: 'Main game client instance',
    path: 'mc',
    icon: '🎮'
  },
  {
    name: 'Player',
    description: 'Local player entity',
    path: 'mc.player',
    icon: '🧑'
  },
  {
    name: 'Level/World',
    description: 'Current world/level',
    path: 'mc.level',
    icon: '🌍'
  },
  {
    name: 'Player Inventory',
    description: 'Player inventory contents',
    path: 'mc.player.getInventory()',
    icon: '🎒'
  },
  {
    name: 'Held Item',
    description: 'Item in main hand',
    path: 'mc.player.getMainHandItem()',
    icon: '🗡️'
  },
  {
    name: 'Game Options',
    description: 'Game settings',
    path: 'mc.options',
    icon: '⚙️'
  },
];

let nodeIdCounter = 0;
function generateNodeId(): string {
  return `node_${++nodeIdCounter}`;
}

export const useBrowserStore = defineStore('browser', () => {
  const roots = ref<BrowserNode[]>([]);
  const selectedNode = ref<BrowserNode | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const customCodeInput = ref('');

  // History for back navigation
  const history = ref<BrowserNode[]>([]);
  const historyIndex = ref(-1);

  const canGoBack = computed(() => historyIndex.value > 0);
  const canGoForward = computed(() => historyIndex.value < history.value.length - 1);

  async function loadEntryPoint(entry: EntryPoint): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      const info = await inspect.evaluateAndDescribe(`return ${entry.path}`);

      const node: BrowserNode = {
        id: generateNodeId(),
        name: entry.name,
        path: entry.path,
        type: 'instance',
        className: info.className,
        shortName: info.shortName,
        displayValue: info.displayValue,
        expandable: !info.isNull && info.fields.length > 0,
        expanded: true,
        loading: false,
        children: info.isNull ? [] : createChildNodes(info, entry.path),
      };

      // Replace all roots with just this one
      roots.value = [node];

      selectNode(node);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading.value = false;
    }
  }

  async function loadFromGroovy(groovyCode: string, name?: string): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      const info = await inspect.evaluateAndDescribe(groovyCode);

      const nodeName = name || 'Result';
      const path = `{ -> ${groovyCode} }()`;

      const node: BrowserNode = {
        id: generateNodeId(),
        name: nodeName,
        path,
        type: 'instance',
        className: info.className,
        shortName: info.shortName,
        displayValue: info.displayValue,
        expandable: !info.isNull && info.fields.length > 0,
        expanded: true,
        loading: false,
        children: info.isNull ? [] : createChildNodes(info, path),
      };

      roots.value.push(node);
      selectNode(node);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading.value = false;
    }
  }

  function createChildNodes(info: inspect.ObjectInfo, parentPath: string): BrowserNode[] {
    const children: BrowserNode[] = [];

    for (const field of info.fields) {
      const fieldPath = `${parentPath}.${field.name}`;
      const isObject = field.valueType === 'object' || field.valueType === 'array';

      children.push({
        id: generateNodeId(),
        name: field.name,
        path: fieldPath,
        type: 'field',
        className: field.className,
        shortName: field.className?.split('.').pop(),
        value: field.value,
        displayValue: formatDisplayValue(field.value, field.valueType),
        expandable: isObject && field.expandable,
        expanded: false,
        loading: false,
      });
    }

    // Sort: expandable objects first, then alphabetically
    children.sort((a, b) => {
      if (a.expandable && !b.expandable) return -1;
      if (!a.expandable && b.expandable) return 1;
      return a.name.localeCompare(b.name);
    });

    return children;
  }

  async function expandNode(node: BrowserNode): Promise<void> {
    if (!node.expandable || node.children) {
      node.expanded = !node.expanded;
      return;
    }

    node.loading = true;
    node.error = undefined;

    try {
      const info = await inspect.evaluateAndDescribe(`return ${node.path}`);

      if (info.isNull) {
        node.children = [];
        node.displayValue = 'null';
      } else {
        node.children = createChildNodes(info, node.path);
        node.className = info.className;
        node.shortName = info.shortName;
        if (info.displayValue) {
          node.displayValue = info.displayValue;
        }
      }

      node.expanded = true;
    } catch (e) {
      node.error = e instanceof Error ? e.message : String(e);
    } finally {
      node.loading = false;
    }
  }

  async function callMethodOnNode(node: BrowserNode, methodName: string, args: string[] = []): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      const argsStr = args.join(', ');
      const methodPath = `${node.path}.${methodName}(${argsStr})`;
      const info = await inspect.evaluateAndDescribe(`return ${methodPath}`);

      const resultNode: BrowserNode = {
        id: generateNodeId(),
        name: `${node.shortName || node.name}.${methodName}()`,
        path: methodPath,
        type: 'instance',
        className: info.className,
        shortName: info.shortName,
        displayValue: info.displayValue,
        expandable: !info.isNull && info.fields.length > 0,
        expanded: true,
        loading: false,
        children: info.isNull ? [] : createChildNodes(info, methodPath),
      };

      roots.value.push(resultNode);
      selectNode(resultNode);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      isLoading.value = false;
    }
  }

  function selectNode(node: BrowserNode) {
    selectedNode.value = node;

    // Add to history
    if (historyIndex.value < history.value.length - 1) {
      history.value = history.value.slice(0, historyIndex.value + 1);
    }
    history.value.push(node);
    historyIndex.value = history.value.length - 1;
  }

  function goBack() {
    if (canGoBack.value) {
      historyIndex.value--;
      selectedNode.value = history.value[historyIndex.value];
    }
  }

  function goForward() {
    if (canGoForward.value) {
      historyIndex.value++;
      selectedNode.value = history.value[historyIndex.value];
    }
  }

  function removeRoot(nodeId: string) {
    const index = roots.value.findIndex(n => n.id === nodeId);
    if (index >= 0) {
      roots.value.splice(index, 1);
    }
  }

  function clearAll() {
    roots.value = [];
    selectedNode.value = null;
    history.value = [];
    historyIndex.value = -1;
    error.value = null;
  }

  return {
    roots,
    selectedNode,
    isLoading,
    error,
    customCodeInput,
    canGoBack,
    canGoForward,
    loadEntryPoint,
    loadFromGroovy,
    expandNode,
    callMethodOnNode,
    selectNode,
    goBack,
    goForward,
    removeRoot,
    clearAll,
  };
});

function formatDisplayValue(value: unknown, valueType: string): string {
  if (value === null || value === undefined) return 'null';

  if (valueType === 'string') {
    const str = String(value);
    if (str.length > 50) return `"${str.slice(0, 50)}..."`;
    return `"${str}"`;
  }

  if (valueType === 'primitive') {
    return String(value);
  }

  if (valueType === 'array') {
    if (Array.isArray(value)) return `Array[${value.length}]`;
    return 'Array';
  }

  if (typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    if (obj.__class) {
      const cls = String(obj.__class);
      const shortName = cls.split('.').pop();
      if (obj.__toString) return `${shortName}: ${String(obj.__toString)}`;
      return shortName || 'Object';
    }
    return 'Object';
  }

  return String(value);
}
