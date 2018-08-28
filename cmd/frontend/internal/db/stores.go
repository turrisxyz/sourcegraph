package db

var (
	AccessTokens       = &accessTokens{}
	DiscussionThreads  = &discussionThreads{}
	DiscussionComments = &discussionComments{}
	GlobalDeps         = &globalDeps{}
	Pkgs               = &pkgs{}
	Repos              = &repos{}
	Phabricator        = &phabricator{}
	UserActivity       = &userActivity{} // DEPRECATED: use package useractivity instead (based on persisted redis cache)
	SavedQueries       = &savedQueries{}
	Orgs               = &orgs{}
	OrgMembers         = &orgMembers{}
	Settings           = &settings{}
	Users              = &users{}
	UserEmails         = &userEmails{}
	SiteConfig         = &siteConfig{}
	CertCache          = &certCache{}

	SurveyResponses = &surveyResponses{}

	ExternalAccounts = &userExternalAccounts{}

	OrgInvitations = &orgInvitations{}

	RegistryExtensions        = &registryExtensions{}
	RegistryExtensionReleases = &registryExtensionReleases{}
)
