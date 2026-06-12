import type { BridgeRequest, BridgeResponse, SessionInfo, ConnectionStatus } from '../types';

type ConnectionListener = (status: ConnectionStatus, info?: SessionInfo) => void;
type MessageListener = (response: BridgeResponse) => void;

class BridgeService {
  private ws: WebSocket | null = null;
  private requestCounter = 0;
  private pendingRequests = new Map<string, {
    resolve: (resp: BridgeResponse) => void;
    reject: (err: Error) => void;
    timeout: ReturnType<typeof setTimeout>;
  }>();

  private connectionListeners: ConnectionListener[] = [];
  private messageListeners: MessageListener[] = [];
  private sessionInfo: SessionInfo | null = null;
  private currentPort: number | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  get isConnected(): boolean {
    return this.ws !== null && this.ws.readyState === WebSocket.OPEN;
  }

  get port(): number | null {
    return this.currentPort;
  }

  get session(): SessionInfo | null {
    return this.sessionInfo;
  }

  onConnectionChange(listener: ConnectionListener): () => void {
    this.connectionListeners.push(listener);
    return () => {
      const idx = this.connectionListeners.indexOf(listener);
      if (idx >= 0) this.connectionListeners.splice(idx, 1);
    };
  }

  onMessage(listener: MessageListener): () => void {
    this.messageListeners.push(listener);
    return () => {
      const idx = this.messageListeners.indexOf(listener);
      if (idx >= 0) this.messageListeners.splice(idx, 1);
    };
  }

  private notifyConnectionChange(status: ConnectionStatus, info?: SessionInfo) {
    this.connectionListeners.forEach(l => l(status, info));
  }

  async connect(port?: number): Promise<SessionInfo> {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    // If port specified, try only that port
    if (port !== undefined) {
      return this.connectToPort(port);
    }

    // When served by the mod itself (http://localhost:<bridge port + 100>),
    // try the owning instance's bridge first so multi-instance setups land on
    // the right client; fall back to the scan if that fails.
    const servedPort = Number(window.location.port);
    if (servedPort >= 9976 && servedPort <= 9986) {
      try {
        return await this.connectToPort(servedPort - 100);
      } catch {
        // Fall through to the scan
      }
    }

    // Otherwise scan ports 9876-9886 (the bridge's 11-port wraparound range)
    const basePort = 9876;
    for (let i = 0; i < 11; i++) {
      try {
        return await this.connectToPort(basePort + i);
      } catch {
        // Try next port
      }
    }

    throw new Error(`Could not find DebugBridge on ports ${basePort}-${basePort + 10}`);
  }

  private async connectToPort(port: number): Promise<SessionInfo> {
    return new Promise((resolve, reject) => {
      this.notifyConnectionChange('connecting');

      const ws = new WebSocket(`ws://127.0.0.1:${port}`);

      const timeout = setTimeout(() => {
        ws.close();
        reject(new Error(`Connection to port ${port} timed out`));
      }, 3000);

      ws.onopen = async () => {
        clearTimeout(timeout);
        this.ws = ws;
        this.currentPort = port;
        this.setupWebSocketHandlers(ws);

        try {
          const status = await this.send('status', {});
          this.sessionInfo = status.result as SessionInfo;
          this.notifyConnectionChange('connected', this.sessionInfo);
          resolve(this.sessionInfo);
        } catch (e) {
          ws.close();
          reject(e);
        }
      };

      ws.onerror = () => {
        clearTimeout(timeout);
        reject(new Error(`WebSocket error on port ${port}`));
      };
    });
  }

  private setupWebSocketHandlers(ws: WebSocket) {
    ws.onmessage = (event) => {
      try {
        const resp: BridgeResponse = JSON.parse(event.data);

        // Notify message listeners
        this.messageListeners.forEach(l => l(resp));

        // Resolve pending request
        const pending = this.pendingRequests.get(resp.id);
        if (pending) {
          clearTimeout(pending.timeout);
          this.pendingRequests.delete(resp.id);
          pending.resolve(resp);
        }
      } catch {
        // Ignore malformed messages
      }
    };

    ws.onclose = () => {
      this.ws = null;
      const wasConnected = this.currentPort !== null;

      // Reject all pending requests
      for (const [, pending] of this.pendingRequests) {
        clearTimeout(pending.timeout);
        pending.reject(new Error('Connection closed'));
      }
      this.pendingRequests.clear();

      this.notifyConnectionChange('disconnected');

      // Auto-reconnect after 2 seconds if was previously connected
      if (wasConnected && this.currentPort) {
        this.reconnectTimer = setTimeout(() => {
          this.connect(this.currentPort!).catch(() => {
            // Reconnect failed, will retry
          });
        }, 2000);
      }
    };

    ws.onerror = () => {
      this.notifyConnectionChange('error');
    };
  }

  disconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    this.currentPort = null;
    this.sessionInfo = null;
  }

  async send(type: string, payload: Record<string, unknown>): Promise<BridgeResponse> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      throw new Error('Not connected');
    }

    const id = `req_${++this.requestCounter}`;
    const req: BridgeRequest = { id, type: type as BridgeRequest['type'], payload };

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error('Request timed out (30s)'));
      }, 30000);

      this.pendingRequests.set(id, { resolve, reject, timeout });
      this.ws!.send(JSON.stringify(req));
    });
  }

  // Convenience methods
  async execute(code: string): Promise<{ success: boolean; result?: unknown; output?: string; error?: string }> {
    const resp = await this.send('execute', { code });
    return {
      success: resp.success,
      result: resp.result,
      output: resp.output,
      error: resp.error,
    };
  }

  async snapshot(): Promise<unknown> {
    const resp = await this.send('snapshot', {});
    if (!resp.success) throw new Error(resp.error || 'Snapshot failed');
    return resp.result;
  }

  async screenshot(downscale = 2, quality = 0.75): Promise<{ path: string; width: number; height: number }> {
    const resp = await this.send('screenshot', { downscale, quality });
    if (!resp.success) throw new Error(resp.error || 'Screenshot failed');
    return resp.result as { path: string; width: number; height: number };
  }

  async runCommand(command: string): Promise<unknown> {
    const cmd = command.startsWith('/') ? command.substring(1) : command;
    const resp = await this.send('runCommand', { command: cmd });
    if (!resp.success) throw new Error(resp.error || 'Command failed');
    return resp.result;
  }

  async search(pattern: string, scope: 'class' | 'method' | 'field' | 'all' = 'all'): Promise<unknown> {
    const resp = await this.send('search', { pattern, scope });
    if (!resp.success) throw new Error(resp.error || 'Search failed');
    return resp.result;
  }

  async getItemTexture(slot: number): Promise<{ base64Png: string; width: number; height: number; spriteName: string }> {
    const resp = await this.send('getItemTexture', { slot });
    if (!resp.success) throw new Error(resp.error || 'Texture fetch failed');
    return resp.result as { base64Png: string; width: number; height: number; spriteName: string };
  }

  async getEntityItemTexture(entityId: number, slot: string): Promise<{ base64Png: string; width: number; height: number; spriteName: string }> {
    const resp = await this.send('getEntityItemTexture', { entityId, slot });
    if (!resp.success) throw new Error(resp.error || 'Entity texture fetch failed');
    return resp.result as { base64Png: string; width: number; height: number; spriteName: string };
  }

  async getItemTextureById(itemId: string): Promise<{ base64Png: string; width: number; height: number; spriteName: string }> {
    const resp = await this.send('getItemTextureById', { itemId });
    if (!resp.success) throw new Error(resp.error || 'Item texture fetch failed');
    return resp.result as { base64Png: string; width: number; height: number; spriteName: string };
  }

  async setEntityGlow(entityId: number, glow: boolean): Promise<void> {
    const resp = await this.send('setEntityGlow', { entityId, glow });
    if (!resp.success) throw new Error(resp.error || 'Set glow failed');
  }

  async getNearbyEntities(range: number, limit: number): Promise<{ entities: unknown[]; count: number }> {
    const resp = await this.send('nearbyEntities', { range, limit });
    if (!resp.success) throw new Error(resp.error || 'Entity query failed');
    return resp.result as { entities: unknown[]; count: number };
  }

  async getEntityDetails(entityId: number): Promise<Record<string, unknown>> {
    const resp = await this.send('entityDetails', { entityId });
    if (!resp.success) throw new Error(resp.error || 'Entity details failed');
    return resp.result as Record<string, unknown>;
  }

  async getLookedAtEntity(range: number): Promise<number | null> {
    const resp = await this.send('lookedAtEntity', { range });
    if (!resp.success) throw new Error(resp.error || 'Looked-at entity query failed');
    const result = resp.result as { entityId: number | null };
    return result.entityId;
  }

  async getNearbyBlocks(range: number, limit: number): Promise<{ blocks: unknown[]; count: number }> {
    const resp = await this.send('nearbyBlocks', { range, limit });
    if (!resp.success) throw new Error(resp.error || 'Block query failed');
    return resp.result as { blocks: unknown[]; count: number };
  }

  async getBlockDetails(x: number, y: number, z: number): Promise<Record<string, unknown>> {
    const resp = await this.send('blockDetails', { x, y, z });
    if (!resp.success) throw new Error(resp.error || 'Block details failed');
    return resp.result as Record<string, unknown>;
  }

  async setBlockGlow(x: number, y: number, z: number, glow: boolean): Promise<void> {
    const resp = await this.send('setBlockGlow', { x, y, z, glow });
    if (!resp.success) throw new Error(resp.error || 'Set block glow failed');
  }

  async clearBlockGlow(): Promise<void> {
    const resp = await this.send('clearBlockGlow', {});
    if (!resp.success) throw new Error(resp.error || 'Clear block glow failed');
  }

  // Session control (gated behind session_control_enabled in debugbridge.json).
  // All three resolve as soon as the request is acknowledged; the operation
  // completes asynchronously in-game — poll snapshot/screenInspect for outcome.

  /** Leave the current world/server (wire type "disconnect" — named to avoid
   *  clashing with the WebSocket disconnect() above). */
  async leaveServer(): Promise<void> {
    const resp = await this.send('disconnect', {});
    if (!resp.success) throw new Error(resp.error || 'Leave server failed');
  }

  async joinServer(address: string, acceptResourcePacks = true): Promise<void> {
    const resp = await this.send('joinServer', { address, acceptResourcePacks });
    if (!resp.success) throw new Error(resp.error || 'Join server failed');
  }

  async quit(): Promise<void> {
    const resp = await this.send('quit', {});
    if (!resp.success) throw new Error(resp.error || 'Quit failed');
  }
}

// Singleton instance
export const bridge = new BridgeService();
