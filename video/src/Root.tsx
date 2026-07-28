import {Composition} from 'remotion';
import {GitGateLaunch} from './GitGateLaunch';

export const RemotionRoot: React.FC = () => (
  <Composition
    id="GitGateLaunch"
    component={GitGateLaunch}
    durationInFrames={1800}
    fps={30}
    width={1920}
    height={1080}
  />
);
