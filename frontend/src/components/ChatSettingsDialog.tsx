import { useEffect, useState } from "react";

const KEY = "crypto-agent-chat-settings";

export type ChatSettings = {
  requireConfirmHint: boolean;
  showToolTrace: boolean;
  systemHint: string;
};

const defaults: ChatSettings = {
  requireConfirmHint: true,
  showToolTrace: false,
  systemHint: "",
};

export function loadChatSettings(): ChatSettings {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...defaults };
    return { ...defaults, ...JSON.parse(raw) };
  } catch {
    return { ...defaults };
  }
}

export function saveChatSettings(s: ChatSettings) {
  localStorage.setItem(KEY, JSON.stringify(s));
}

type Props = {
  open: boolean;
  onClose: () => void;
  onSave?: (s: ChatSettings) => void;
};

export default function ChatSettingsDialog({ open, onClose, onSave }: Props) {
  const [s, setS] = useState<ChatSettings>(defaults);

  useEffect(() => {
    if (open) setS(loadChatSettings());
  }, [open]);

  if (!open) return null;

  function save() {
    saveChatSettings(s);
    onSave?.(s);
    onClose();
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Chat 对话设置</h3>
        <label style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 12 }}>
          <input
            type="checkbox"
            checked={s.requireConfirmHint}
            onChange={(e) => setS({ ...s, requireConfirmHint: e.target.checked })}
          />
          配置变更时显示确认提示
        </label>
        <label style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 12 }}>
          <input
            type="checkbox"
            checked={s.showToolTrace}
            onChange={(e) => setS({ ...s, showToolTrace: e.target.checked })}
          />
          显示工具调用轨迹（调试）
        </label>
        <label>附加系统提示（可选）</label>
        <input
          value={s.systemHint}
          placeholder="会在发送时附加到消息前"
          onChange={(e) => setS({ ...s, systemHint: e.target.value })}
        />
        <div className="row">
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="button" onClick={save}>
            保存
          </button>
        </div>
      </div>
    </div>
  );
}
