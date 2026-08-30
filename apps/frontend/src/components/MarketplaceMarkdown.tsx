import { inlineParts, parseMarkdown } from './marketplace.ts'
import styles from './marketplace.module.scss'

/** A line's `**bold**` and `` `code` ``, as elements. */
function Inline({ text }: { text: string }) {
  return (
    <>
      {inlineParts(text).map((part, i) =>
        part.kind === 'bold' ? (
          <strong key={i}>{part.text}</strong>
        ) : part.kind === 'code' ? (
          <code key={i}>{part.text}</code>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </>
  )
}

/**
 * An item's description: paragraphs, headings, lists, bold, code. Enough for what a person
 * writes about a server or a template; nothing that needs a library.
 */
export function MarketplaceMarkdown({ text }: { text: string }) {
  return (
    <div className={styles.description}>
      {parseMarkdown(text).map((block, i) => {
        switch (block.type) {
          case 'h':
            return (
              <h4 key={i}>
                <Inline text={block.text} />
              </h4>
            )
          case 'ul':
            return (
              <ul key={i}>
                {block.items.map((item, j) => (
                  <li key={j}>
                    <Inline text={item} />
                  </li>
                ))}
              </ul>
            )
          case 'ol':
            return (
              <ol key={i}>
                {block.items.map((item, j) => (
                  <li key={j}>
                    <Inline text={item} />
                  </li>
                ))}
              </ol>
            )
          case 'code':
            return (
              <pre key={i}>
                <code>{block.text}</code>
              </pre>
            )
          default:
            return (
              <p key={i}>
                <Inline text={block.text} />
              </p>
            )
        }
      })}
    </div>
  )
}
