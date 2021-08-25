/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */

pub use smithy_client::retry::Config as RetryConfig;

use aws_auth::middleware::CredentialsStage;
use aws_endpoint::AwsEndpointStage;
use aws_http::user_agent::UserAgentStage;
use aws_sig_auth::middleware::SigV4SigningStage;
use aws_sig_auth::signer::SigV4Signer;
pub use smithy_http::result::{SdkError, SdkSuccess};
use smithy_http_tower::map_request::{AsyncMapRequestLayer, MapRequestLayer};
use std::fmt::Debug;
use tower::layer::util::Stack;
use tower::ServiceBuilder;

pub fn raw_client(shared_config: &aws_types::config::Config) -> Client<DynConnector> {
    Client::new(shared_config.connector().clone())
}

type AwsMiddlewareStack = Stack<
    MapRequestLayer<SigV4SigningStage>,
    Stack<
        AsyncMapRequestLayer<CredentialsStage>,
        Stack<MapRequestLayer<UserAgentStage>, MapRequestLayer<AwsEndpointStage>>,
    >,
>;

#[derive(Debug, Default)]
#[non_exhaustive]
pub struct AwsMiddleware;
impl<S> tower::Layer<S> for AwsMiddleware {
    type Service = <AwsMiddlewareStack as tower::Layer<S>>::Service;

    fn layer(&self, inner: S) -> Self::Service {
        let credential_provider = AsyncMapRequestLayer::for_mapper(CredentialsStage::new());
        let signer = MapRequestLayer::for_mapper(SigV4SigningStage::new(SigV4Signer::new()));
        let endpoint_resolver = MapRequestLayer::for_mapper(AwsEndpointStage);
        let user_agent = MapRequestLayer::for_mapper(UserAgentStage::new());
        // These layers can be considered as occurring in order, that is:
        // 1. Resolve an endpoint
        // 2. Add a user agent
        // 3. Acquire credentials
        // 4. Sign with credentials
        // (5. Dispatch over the wire)
        ServiceBuilder::new()
            .layer(endpoint_resolver)
            .layer(user_agent)
            .layer(credential_provider)
            .layer(signer)
            .service(inner)
    }
}

/// AWS Service Client
///
/// Hyper-based AWS Service Client. Most customers will want to construct a client with
/// [`Client::https`](smithy_client::Client::https). For testing & other more advanced use cases, a
/// custom connector may be used via [`Client::new(connector)`](smithy_client::Client::new).
///
/// The internal connector must implement the following trait bound to be used to dispatch requests:
/// ```rust,ignore
///    S: Service<http::Request<SdkBody>, Response = http::Response<hyper::Body>>
///        + Send
///        + Clone
///        + 'static,
///    S::Error: Into<BoxError> + Send + Sync + 'static,
///    S::Future: Send + 'static,
/// ```
pub type Client<C = DynConnector> = smithy_client::Client<C, AwsMiddleware>;

#[doc(inline)]
pub use smithy_client::erase::DynConnector;

#[doc(inline)]
pub use smithy_client::bounds::SmithyConnector;