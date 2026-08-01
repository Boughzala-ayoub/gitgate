import {Composition} from 'remotion';
import {GitGateLaunch} from './GitGateLaunch';
import {GitGateMarketing} from './GitGateMarketing';

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="GitGateLaunch"
      component={GitGateLaunch}
      durationInFrames={1800}
      fps={30}
      width={1920}
      height={1080}
    />
    <Composition
      id="GitGateMarketing"
      component={GitGateMarketing}
      durationInFrames={2700}
      fps={30}
      width={1920}
      height={1080}
    />
  </>
);
