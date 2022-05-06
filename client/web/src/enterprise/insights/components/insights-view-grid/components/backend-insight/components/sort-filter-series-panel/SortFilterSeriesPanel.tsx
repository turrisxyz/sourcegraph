import { useState } from 'react'

import classNames from 'classnames'

import { Button, ButtonGroup } from '@sourcegraph/wildcard'

import { SeriesDisplayOptions, SeriesSortOptionsInput } from '../../../../../../core/types/insight/common'

import styles from './SortFilterSeriesPanel.module.scss'

const getClasses = (selected: SeriesSortOptionsInput, value: SeriesSortOptionsInput): string => {
    const isSelected = selected.mode === value.mode && selected.direction === value.direction
    return classNames({ [styles.selected]: isSelected, [styles.unselected]: !isSelected })
}

interface SortFilterSeriesPanelProps {
    value: SeriesDisplayOptions
    onChange: (parameter: SeriesDisplayOptions) => void
}

export const SortFilterSeriesPanel: React.FunctionComponent<SortFilterSeriesPanelProps> = ({ value, onChange }) => {
    const [selected, setSelected] = useState(value.sortOptions)
    const [seriesCount, setSeriesCount] = useState(value.limit)

    const handleToggle = (value: SeriesSortOptionsInput): void => {
        setSelected(value)
        onChange({ limit: seriesCount, sortOptions: value })
    }

    const handleChange: React.ChangeEventHandler<HTMLInputElement> = event => {
        const count = parseInt(event.target.value, 10) || 1
        setSeriesCount(count)
        onChange({ limit: count, sortOptions: selected })
    }

    return (
        <section>
            <section className={classNames(styles.togglesContainer)}>
                <div className="d-flex flex-column">
                    <small className={styles.label}>Sort by result count</small>
                    <ButtonGroup className={styles.toggleGroup}>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'RESULT_COUNT', direction: 'DESC' }}
                            onClick={handleToggle}
                        >
                            Highest
                        </ToggleButton>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'RESULT_COUNT', direction: 'ASC' }}
                            onClick={handleToggle}
                        >
                            Lowest
                        </ToggleButton>
                    </ButtonGroup>
                </div>
                <div className="d-flex flex-column">
                    <small className={styles.label}>Sort by name</small>
                    <ButtonGroup className={styles.toggleGroup}>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'LEXICOGRAPHICAL', direction: 'ASC' }}
                            onClick={handleToggle}
                        >
                            A-Z
                        </ToggleButton>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'LEXICOGRAPHICAL', direction: 'DESC' }}
                            onClick={handleToggle}
                        >
                            Z-A
                        </ToggleButton>
                    </ButtonGroup>
                </div>
                <div className="d-flex flex-column">
                    <small className={styles.label}>Sort by date added</small>
                    <ButtonGroup className={styles.toggleGroup}>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'DATE_ADDED', direction: 'ASC' }}
                            onClick={handleToggle}
                        >
                            Latest
                        </ToggleButton>
                        <ToggleButton
                            selected={selected}
                            value={{ mode: 'DATE_ADDED', direction: 'DESC' }}
                            onClick={handleToggle}
                        >
                            Oldest
                        </ToggleButton>
                    </ButtonGroup>
                </div>
            </section>
            <footer className={styles.footer}>
                <span>Number of data series</span>
                <input
                    type="number"
                    step="1"
                    value={seriesCount}
                    className="form-control form-control-sm"
                    onChange={handleChange}
                />
            </footer>
        </section>
    )
}

interface ToggleButtonProps {
    selected: SeriesSortOptionsInput
    value: SeriesSortOptionsInput
    onClick: (value: SeriesSortOptionsInput) => void
}

const ToggleButton: React.FunctionComponent<ToggleButtonProps> = ({ selected, value, children, onClick }) => (
    <Button variant="secondary" size="sm" className={getClasses(selected, value)} onClick={() => onClick(value)}>
        {children}
    </Button>
)
