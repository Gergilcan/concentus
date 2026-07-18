import { Component, type ErrorInfo, type ReactNode } from 'react'
import styles from './errorboundary.module.scss'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error:', error, info.componentStack)
  }

  private reset = () => {
    this.setState({ error: null })
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className={styles.boundary}>
        <div className={styles.card}>
          <h2 className={styles.title}>Something went wrong</h2>
          <p className={styles.message}>{error.message || String(error)}</p>
          <div className={styles.actions}>
            <button className={styles.retryBtn} onClick={this.reset}>
              Try again
            </button>
            <button className={styles.reloadBtn} onClick={() => location.reload()}>
              Reload app
            </button>
          </div>
        </div>
      </div>
    )
  }
}
