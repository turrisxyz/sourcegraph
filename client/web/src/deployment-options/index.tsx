import { FunctionComponent } from 'react'

import classNames from 'classnames'
import * as H from 'history'
import ArrowRightIcon from 'mdi-react/ArrowRightIcon'

import { ButtonLink } from '@sourcegraph/wildcard'

import { shouldRedirectToWelcome } from '../auth/SignInSignUpCommon'
import { PageTitle } from '../components/PageTitle'
import { PageRoutes } from '../routes.constants'

import styles from './deployment-options.module.scss'

export const BestForTitle: FunctionComponent = () => (
    <div className={classNames('text-uppercase font-weight-bolder mb-1', styles.bestForTitle)}>Best For</div>
)

interface IProps {
    history: H.History
}

export const DeploymentOptions: FunctionComponent<IProps> = ({ history }) => (
    <div className="flex flex-1 mt-5">
        <PageTitle title="Choose your deployment model" />
        <div className={styles.hero}>
            <div className="container-xl py-5">
                <h1 className="display-3 mb-2">
                    <strong>What's best for you?</strong>
                </h1>
                <p>From Amazon to Uber, the world's best developers use Sourcegraph every day.</p>
            </div>
        </div>
        <div className={`${styles.root} ${styles.getStartedPage}`}>
            <div className="container-xl">
                <div className="row">
                    <section className="col-lg-6 px-4 py-5 my-5">
                        <h1 className="font-bold">Self-Hosted</h1>

                        <p>
                            Deploy and control Sourcegraph in your own infrastructure, or use Docker to install locally.
                        </p>

                        <BestForTitle />
                        <p>Teams and enterprises</p>

                        <p>
                            Collaborate with your team on any code host (including private hosts) and access advanced
                            security functionality.
                        </p>

                        <ButtonLink variant="merged" to={PageRoutes.SelfHostedDeploymentOption}>
                            Try Self-Hosted <ArrowRightIcon />
                        </ButtonLink>
                    </section>

                    <section className="col-lg-6 px-4 py-5 my-5">
                        <h1 className="font-weight-bold">Cloud</h1>

                        <p>Sync your code from GitHub.com or GitLab.com. No technical setup is required.</p>

                        <BestForTitle />
                        <p>Individual developers</p>

                        <p>
                            Search all your repositories and the open source universe without having to install or
                            manage a deployment.
                        </p>

                        <ButtonLink
                            variant="merged"
                            to={shouldRedirectToWelcome() ? PageRoutes.Welcome : PageRoutes.Search}
                        >
                            Continue with Cloud <ArrowRightIcon />
                        </ButtonLink>
                    </section>
                </div>
            </div>
        </div>
    </div>
)

export default DeploymentOptions