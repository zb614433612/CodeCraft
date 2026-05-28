import { request } from '@/utils/http-client'

export interface P2pStatus {
  running: boolean
  peerId: string
  port: number
  peerCount: number
  certFingerprint: string
  myName: string
}

export interface P2pAddressInfo {
  addresses: string[]
  bestAddress: string | null
  hasIpv6: boolean
}

export interface P2pSignalingResult {
  qrCodeBase64: string
  connectionString: string
  compactString: string
  qrError?: string
}

export interface P2pPeer {
  peerId: string
  address: string
  name: string
  displayName: string
  remark: string
  connectedAt: number
  lastHeartbeat: number
  online: boolean
}

export interface ChatMessage {
  name: string
  content: string
  messageType?: string
  agentConfigId?: number
  agentName?: string
}

export interface HistoryMessage {
  name: string
  content: string
  direction: string
  messageType?: string
  agentConfigId?: number
  agentName?: string
  time: string
}

export interface AgentAuthItem {
  id: number
  name: string
  avatar?: string
}

// ==================== API ====================

export async function getP2pStatus() {
  const res = await request<P2pStatus>('/p2p/status')
  return res.data
}

export async function getP2pAddress() {
  const res = await request<P2pAddressInfo>('/p2p/address')
  return res.data
}

export async function getP2pName() {
  const res = await request<{ name: string }>('/p2p/name')
  return res.data
}

export async function setP2pName(name: string) {
  const res = await request<{ name: string; status: string }>('/p2p/name', {
    method: 'POST',
    body: JSON.stringify({ name })
  })
  return res.data
}

export async function generateQrCode() {
  const res = await request<P2pSignalingResult>('/p2p/qrcode', { method: 'POST' })
  return res.data
}

export async function parseQrCode(image: string) {
  const res = await request<{ peerId: string; address: string; fingerprint: string }>('/p2p/qrcode/parse', {
    method: 'POST',
    body: JSON.stringify({ image })
  })
  return res.data
}

export async function connectPeer(connectionString: string) {
  const res = await request<{ status: string; peerId: string; address: string; channelActive: boolean }>('/p2p/connect', {
    method: 'POST',
    body: JSON.stringify({ connectionString })
  })
  return res.data
}

export async function disconnectPeer(peerId: string) {
  const res = await request<{ status: string; peerId: string }>(`/p2p/disconnect/${peerId}`, {
    method: 'POST'
  })
  return res.data
}

export async function getPeers() {
  const res = await request<P2pPeer[]>('/p2p/peers')
  return res.data
}

export async function sendMessage(peerId: string, content: string) {
  const res = await request<{ status: string }>(`/p2p/send/${peerId}`, {
    method: 'POST',
    body: JSON.stringify({ content })
  })
  return res.data
}

export async function pollMessages(peerId: string) {
  const res = await request<ChatMessage[]>(`/p2p/messages/${peerId}`)
  return res.data
}

export async function getHistory(peerId: string) {
  const res = await request<HistoryMessage[]>(`/p2p/messages/${peerId}/history`)
  return res.data
}

export async function deleteMessages(peerId: string) {
  const res = await request<{ status: string }>(`/p2p/messages/${peerId}`, { method: 'DELETE' })
  return res.data
}

export async function setRemark(peerId: string, remark: string) {
  const res = await request<{ status: string; remark: string }>(`/p2p/peer/${peerId}/remark`, {
    method: 'PUT',
    body: JSON.stringify({ remark })
  })
  return res.data
}

// ==================== Agent 授权与调用 ====================

/** 授权 Agent 给对方 */
export async function grantAgentAuth(peerId: string, agentConfigIds: number[]) {
  const res = await request<{ status: string; peerId: string; agentCount: number }>(
    `/p2p/agent-auth/grant/${peerId}`,
    { method: 'POST', body: JSON.stringify({ agentConfigIds }) }
  )
  return res.data
}

/** 取消授权 */
export async function cancelAgentAuth(peerId: string, agentConfigIds: number[]) {
  const res = await request<{ status: string; peerId: string }>(
    `/p2p/agent-auth/cancel/${peerId}`,
    { method: 'POST', body: JSON.stringify({ agentConfigIds }) }
  )
  return res.data
}

/** 获取我授权给某节点的 Agent 列表 */
export async function getMyAuthToPeer(peerId: string) {
  const res = await request<AgentAuthItem[]>(`/p2p/agent-auth/sent/${peerId}`)
  return res.data
}

/** 获取某节点授权给我的 Agent 列表（我可调用的） */
export async function getPeerAuthToMe(peerId: string) {
  const res = await request<AgentAuthItem[]>(`/p2p/agent-auth/received/${peerId}`)
  return res.data
}

/** 调用对方的 Agent */
export async function invokeAgent(peerId: string, agentConfigId: number, message: string) {
  const res = await request<{ status: string; requestId: string }>(
    `/p2p/agent/invoke/${peerId}`,
    { method: 'POST', body: JSON.stringify({ agentConfigId, message }) }
  )
  return res.data
}
