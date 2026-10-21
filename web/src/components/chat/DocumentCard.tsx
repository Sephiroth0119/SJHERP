import type { ResolvedDocumentRef } from '../../security/documentRef';
import { hashForDocument } from '../../security/documentRef';

interface DocumentCardProps {
  document: ResolvedDocumentRef;
}

/**
 * 消息内嵌单据卡片：只展示类型 + 单号，点击后走 hash 路由打开传统工作台。
 * 权限过滤在 visibleDocumentRefs 完成，本组件不拉详情、不展示金额。
 */
export function DocumentCard({ document }: DocumentCardProps) {
  const open = () => {
    window.location.hash = hashForDocument(document);
  };

  return (
    <button
      type="button"
      className="document-card"
      onClick={open}
      title={`打开${document.title} ${document.docNo}`}
    >
      <span className="document-card-type">{document.title}</span>
      <span className="document-card-docno">{document.docNo}</span>
    </button>
  );
}
